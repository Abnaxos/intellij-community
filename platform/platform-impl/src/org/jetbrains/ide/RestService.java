/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.ide;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.MalformedJsonException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.containers.ContainerUtil;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.io.Responses;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Document your service using <a href="http://apidocjs.com">apiDoc</a>. To extract big example from source code, consider to use *.coffee file near your source file.
 * (or Python/Ruby, but coffee recommended because it's plugin is lightweight). See {@link AboutHttpService} for example.
 */
public abstract class RestService extends HttpRequestHandler {
  protected static final Logger LOG = Logger.getInstance(RestService.class);

  @Override
  public final boolean isSupported(@NotNull FullHttpRequest request) {
    if (!isMethodSupported(request.method())) {
      return false;
    }

    String prefix = "rest";
    String uri = request.uri();
    String serviceName = getServiceName();
    int minLength = 1 + prefix.length() + 1 + serviceName.length();
    if (uri.length() >= minLength &&
        uri.charAt(0) == '/' &&
        uri.regionMatches(true, 1, prefix, 0, prefix.length()) &&
        uri.regionMatches(true, 2 + prefix.length(), serviceName, 0, serviceName.length())) {
      if (uri.length() == minLength) {
        return true;
      }
      else {
        char c = uri.charAt(minLength);
        return c == '/' || c == '?';
      }
    }
    return false;
  }

  @NotNull
  /**
   * Use human-readable name or UUID if it is an internal service.
   */
  protected abstract String getServiceName();

  protected abstract boolean isMethodSupported(@NotNull HttpMethod method);

  @Override
  public final boolean process(@NotNull QueryStringDecoder urlDecoder, @NotNull FullHttpRequest request, @NotNull ChannelHandlerContext context) throws IOException {
    try {
      String error = execute(urlDecoder, request, context);
      if (error != null) {
        Responses.sendStatus(HttpResponseStatus.BAD_REQUEST, context.channel(), error, request);
      }
    }
    catch (Throwable e) {
      HttpResponseStatus status;
      // JsonReader exception
      //noinspection InstanceofCatchParameter
      if (e instanceof MalformedJsonException || (e instanceof IllegalStateException && e.getMessage().startsWith("Expected a "))) {
        LOG.warn(e);
        status = HttpResponseStatus.BAD_REQUEST;
      }
      else {
        LOG.error(e);
        status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
      }
      Responses.sendStatus(status, context.channel(), ExceptionUtil.getThrowableText(e), request);
    }
    return true;
  }

  @Nullable("error text or null if successful")
  /**
   * Return error or send response using {@link #sendOk(FullHttpRequest, ChannelHandlerContext)}, {@link #send(BufferExposingByteArrayOutputStream, FullHttpRequest, ChannelHandlerContext)}
   */
  public abstract String execute(@NotNull QueryStringDecoder urlDecoder, @NotNull FullHttpRequest request, @NotNull ChannelHandlerContext context) throws IOException;

  @NotNull
  protected static JsonReader createJsonReader(@NotNull FullHttpRequest request) {
    JsonReader reader = new JsonReader(new InputStreamReader(new ByteBufInputStream(request.content()), CharsetToolkit.UTF8_CHARSET));
    reader.setLenient(true);
    return reader;
  }

  @Nullable
  protected static Project guessProject() {
    IdeFrame lastFocusedFrame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
    Project project = lastFocusedFrame == null ? null : lastFocusedFrame.getProject();
    if (project == null) {
      Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
      return openProjects.length > 0 ? openProjects[0] : null;
    }
    return project;
  }

  protected static void sendOk(@NotNull FullHttpRequest request, @NotNull ChannelHandlerContext context) {
    Responses.send(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK), context.channel(), request);
  }

  protected static void send(@NotNull BufferExposingByteArrayOutputStream byteOut, @NotNull FullHttpRequest request, @NotNull ChannelHandlerContext context) {
    HttpResponse response = Responses.response("application/json", Unpooled.wrappedBuffer(byteOut.getInternalBuffer(), 0, byteOut.size()));
    Responses.addNoCache(response);
    Responses.send(response, context.channel(), request);
  }

  protected static boolean getBooleanParameter(@NotNull String name, @NotNull QueryStringDecoder urlDecoder) {
    List<String> values = urlDecoder.parameters().get(name);
    if (ContainerUtil.isEmpty(values)) {
      return false;
    }

    String value = values.get(values.size() - 1);
    // if just name specified, so, true
    return value.isEmpty() || Boolean.parseBoolean(value);
  }
}
