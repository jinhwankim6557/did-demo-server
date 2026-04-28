/*
 * Copyright 2024 OmniOne.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.omnione.did.demo.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

/**
 * OID4VC 발급용 Issuer 서버 프록시.
 * 브라우저에서 Issuer 서버를 직접 호출하면 CORS 프리플라이트가 막히므로,
 * demo-server 가 동일 오리진 엔드포인트로 받아 서버-to-서버 HTTP 호출을 대신한다.
 */
@RestController
@RequestMapping("/demo/api")
@RequiredArgsConstructor
@Slf4j
public class OID4VCController {

    private static final String ISSUER_QR_PATH = "/qr-data/generate-qr";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final Environment environment;

    @PostMapping("/oid4vc-offer")
    public ResponseEntity<?> proxyOfferQr(@RequestBody Map<String, Object> body) {
        String issuerUrl = environment.getProperty("issuer.url", "");
        if (issuerUrl.isBlank()) {
            log.warn("issuer.url not configured — cannot forward OID4VC offer request");
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Issuer server is not configured. Set it via Server Settings."
            ));
        }

        String targetUrl;
        try {
            URI origin = URI.create(issuerUrl.trim()).resolve("/");
            targetUrl = origin.toString().replaceAll("/$", "") + ISSUER_QR_PATH;
        } catch (IllegalArgumentException e) {
            log.error("Invalid issuer.url: {}", issuerUrl, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Invalid Issuer URL: " + issuerUrl
            ));
        }

        RestTemplate restTemplate = new RestTemplate();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        restTemplate.setRequestFactory(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("Forwarding OID4VC offer → {} (userId={})", targetUrl, body.get("userId"));

        try {
            ResponseEntity<Object> upstream = restTemplate.postForEntity(targetUrl, request, Object.class);
            return ResponseEntity.status(upstream.getStatusCode()).body(upstream.getBody());
        } catch (HttpStatusCodeException e) {
            log.warn("Issuer returned {} for OID4VC offer: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "success", false,
                    "message", "Issuer responded with error: " + e.getStatusCode(),
                    "upstreamBody", e.getResponseBodyAsString()
            ));
        } catch (Exception e) {
            log.error("Failed to reach Issuer at {}", targetUrl, e);
            return ResponseEntity.status(502).body(Map.of(
                    "success", false,
                    "message", "Failed to reach Issuer: " + e.getMessage()
            ));
        }
    }
}
