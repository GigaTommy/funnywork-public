package com.funnywork;

/** Account session policy and identity-service client binding; never grants permissions. */
public enum AccountClient {
    DESKTOP(ManagementIdentity.DESKTOP_CLIENT), IOS(ManagementIdentity.MOBILE_CLIENT), ANDROID(ManagementIdentity.ANDROID_CLIENT);
    private final String identityClient;
    AccountClient(String identityClient) { this.identityClient = identityClient; }
    public String identityClient() { return identityClient; }
    public boolean mobile() { return this != DESKTOP; }
    public static AccountClient from(String value) {
        if (value == null || value.equals("desktop")) return DESKTOP;
        return switch (value) {
            case "ios" -> IOS;
            case "android" -> ANDROID;
            default -> throw new ApiException(400, "INVALID_CLIENT", "不支持的客户端");
        };
    }
}
