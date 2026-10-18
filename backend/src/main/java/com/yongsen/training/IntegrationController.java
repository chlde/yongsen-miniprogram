package com.yongsen.training;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class IntegrationController {
    private final WxProvider wx;
    private final NotificationService notifications;
    private final Environment environment;

    public IntegrationController(
            WxProvider wx, NotificationService notifications, Environment environment) {
        this.wx = wx;
        this.notifications = notifications;
        this.environment = environment;
    }

    @GetMapping("/health")
    public Object health() {
        return Data.map(
                "status",
                "UP",
                "providerMode",
                wx.mode(),
                "profile",
                environment.matchesProfiles("local", "test") ? "local" : "prod");
    }

    @GetMapping("/integrations/wechat/yongsen/callback")
    public String verify(@RequestParam Map<String, String> query) {
        return wx.verifyEcho(query);
    }

    @PostMapping("/integrations/wechat/yongsen/callback")
    public String callback(@RequestParam Map<String, String> query, @RequestBody String body) {
        notifications.callback(wx.decodeCallback(query, body), false);
        return "success";
    }
}
