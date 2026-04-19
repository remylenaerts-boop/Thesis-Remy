package com.example.thesisremy.serviceandcomponents;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/*
    Configures the WebSocket connection used to receive camera frames from the glasses.

    WebSocket is a two-way persistent connection between the server and a client.
    We use it here because the glasses send raw binary image data (JPEG frames),
    which is not well suited for regular HTTP requests. WebSocket keeps the connection
    open and lets the glasses stream frames continuously without the overhead of
    opening a new connection for each frame.

    This class does two things:
      1. Registers FrameWebSocketHandler on the /frames path so the glasses know
         where to connect to send their camera feed.
      2. Increases the message buffer size to 512 KB so that larger JPEG frames
         are not rejected. The default Spring buffer is too small for camera images.
*/
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final FrameWebSocketHandler frameHandler;

    public WebSocketConfig(FrameWebSocketHandler frameHandler) {
        this.frameHandler = frameHandler;
    }

    // Connects the /frames URL path to the handler that processes incoming frames
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(frameHandler, "/frames")
                .setAllowedOrigins("*"); // allow connections from any device on the network
    }

    /*
        Raises the WebSocket buffer size to 512 KB.

        By default Spring only allocates a small buffer for incoming WebSocket messages.
        A single JPEG frame from the glasses camera can easily exceed that limit, which
        would cause the connection to drop. 512 KB gives comfortable headroom for the
        image sizes this system is expected to handle.
    */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(512 * 1024); // 512 KB for JPEG frames
        container.setMaxTextMessageBufferSize(512 * 1024);
        return container;
    }
}
