package com.hybrid.platform.web;

import com.hybrid.platform.storage.LocalFileStorage;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/** Pretty URLs for the static storefront/admin pages and the public media area for product images. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String SF = "/{sf:shop|digital}";

    private final LocalFileStorage storage;

    public WebConfig(LocalFileStorage storage) {
        this.storage = storage;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Store home, category, product and content pages are server-rendered by SeoPageController.
        registry.addViewController(SF + "/wishlist").setViewName("forward:/store/wishlist.html");
        registry.addViewController(SF + "/cart").setViewName("forward:/store/cart.html");
        registry.addViewController(SF + "/checkout").setViewName("forward:/store/checkout.html");
        registry.addViewController(SF + "/pay/{orderNumber}").setViewName("forward:/store/pay.html");
        registry.addViewController(SF + "/admin").setViewName("forward:/admin/index.html");
        registry.addViewController(SF + "/admin/").setViewName("forward:/admin/index.html");
        registry.addViewController(SF + "/admin/login").setViewName("forward:/admin/login.html");
        registry.addViewController("/account").setViewName("forward:/account/index.html");
        registry.addViewController("/account/").setViewName("forward:/account/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = storage.publicRoot().toAbsolutePath().toUri().toString();
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }
}
