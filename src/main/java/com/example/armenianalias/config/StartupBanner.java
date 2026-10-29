package com.example.armenianalias.config;

import com.example.armenianalias.game.WordBank;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Prints the address phones on the same Wi-Fi should open. */
@Component
public class StartupBanner implements ApplicationListener<ApplicationReadyEvent> {

    private final WordBank wordBank;

    public StartupBanner(WordBank wordBank) {
        this.wordBank = wordBank;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int port = 8080;
        if (event.getApplicationContext() instanceof WebServerApplicationContext context
                && context.getWebServer() != null) {
            port = context.getWebServer().getPort();
        }
        String lan = lanAddress();
        System.out.println();
        System.out.println("  ╭──────────────────────────────────────────────╮");
        System.out.println("  │   ՀԱՅԿԱԿԱՆ ԱԼԻԱՍ  ·  Armenian Alias          │");
        System.out.println("  ╰──────────────────────────────────────────────╯");
        System.out.println("   Բառարան / словарь:  " + wordBank.size() + " բառ");
        System.out.println("   На этом компьютере: http://localhost:" + port);
        if (lan != null) {
            System.out.println("   С телефонов (Wi-Fi): http://" + lan + ":" + port);
        }
        System.out.println();
    }

    private String lanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Not worth failing startup over a cosmetic banner.
        }
        return null;
    }
}
