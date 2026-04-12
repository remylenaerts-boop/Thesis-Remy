package com.example.thesisremy.serviceandcomponents;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private FrameWebSocketHandler frameHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(frameHandler, "/frames")
                .setAllowedOrigins("*");
    }

    // RAISE THE BUFFER TO 512KB — WELL ABOVE THE ~83KB JPEG FRAMES
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        container.setMaxTextMessageBufferSize(512 * 1024);
        return container;
    }
}