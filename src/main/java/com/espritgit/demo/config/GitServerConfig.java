package com.espritgit.demo.config;


import com.example.jgitpersistentdemo.gitserver.GitServerRepositoryResolver;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.http.server.resolver.DefaultReceivePackFactory;
import org.eclipse.jgit.http.server.resolver.DefaultUploadPackFactory;
import org.eclipse.jgit.transport.ReceivePack;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity // Enable Spring Security's web security support
public class GitServerConfig {

    @Value("${git.repositories.base-path}")
    private String repositoriesBasePath;

    @Value("${git.server.servlet-path}")
    private String gitServletPath; // e.g., /gitserver/*

    @Bean
    public ServletRegistrationBean<GitServlet> gitServletRegistrationBean() {
        GitServlet servlet = new GitServlet();

        // Set the custom repository resolver
        servlet.setRepositoryResolver(new GitServerRepositoryResolver(repositoriesBasePath));

        // Configure UploadPack (for clone/fetch) - allow all by default
        servlet.setUploadPackFactory(new DefaultUploadPackFactory());

        // Configure ReceivePack (for push)
        // This factory will be used to create ReceivePack instances for each push.
        // We enable pushes by default here.
        servlet.setReceivePackFactory(new DefaultReceivePackFactory());


        ServletRegistrationBean<GitServlet> registration = new ServletRegistrationBean<>(servlet, gitServletPath);
        registration.setName("GitServlet");
        return registration;
    }

    // --- Spring Security Configuration ---
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        String serviceRpcPattern = gitServletPath.replace("*", "**/*service=git-receive-pack");
        String infoRefsPattern = gitServletPath.replace("*", "**info/refs*"); // Path for git push initial negotiation

        http
                .authorizeHttpRequests(authz -> authz
                        // Allow anonymous access for git clone/fetch (UploadPack related)
                        .requestMatchers(HttpMethod.GET, gitServletPath.replace("*", "**")).permitAll() // general read access
                        .requestMatchers(HttpMethod.POST, gitServletPath.replace("*", "**/*service=git-upload-pack")).permitAll() // for fetch/clone

                        // Require authentication for git push (ReceivePack related)
                        .requestMatchers(HttpMethod.POST, serviceRpcPattern).authenticated()
                        .requestMatchers(HttpMethod.GET, infoRefsPattern + "*service=git-receive-pack").authenticated() // some clients check this with GET

                        .anyRequest().permitAll() // Allow other requests if any (e.g. our /api/git/* endpoints)
                )
                .csrf(csrf -> csrf
                        // Disable CSRF for the GitServlet path, as Git clients don't typically send CSRF tokens
                        .ignoringRequestMatchers(gitServletPath.replace("*", "**"))
                )
                .httpBasic(withDefaults()); // Enable HTTP Basic authentication

        return http.build();
    }

    // In-memory user details service for basic auth (using properties)
    // Spring Boot auto-configures this if spring.security.user.name is set.
    // If you need more users or different storage, provide a custom UserDetailsService bean.
    // Example of explicit bean if not relying on auto-config:
    /*
    @Bean
    public UserDetailsService userDetailsService(
            @Value("${spring.security.user.name}") String username,
            @Value("${spring.security.user.password}") String password,
            @Value("${spring.security.user.roles}") String roles) {
        UserDetails user = User.builder()
            .username(username)
            .password("{noop}" + password) // {noop} for plain text, use bcrypt in prod
            .roles(roles.split(","))
            .build();
        return new InMemoryUserDetailsManager(user);
    }
    */
}