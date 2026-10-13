package frb.axeron.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    public static final String MANAGER_APPLICATION_ID = "dev.axora.manager";
    public static final String SHIZUKU_MANAGER_APPLICATION_ID = "moe.shizuku.privileged.api";
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";
    public static final String REQUEST_BINDER_AXRUNTIME = "AxRuntime.REQUEST_BINDER";

    /**
     * On-demand binder request, sent by the manager straight to the detached server.
     *
     * Deliberately a different action from {@link #REQUEST_BINDER_AXRUNTIME}: the manager app
     * itself declares receivers for that one (it brokers binders to third-party clients), so
     * reusing it would make the manager's own {@code ShellRequestHandlerActivity} launch in
     * response to its own request. The server registers this one dynamically instead.
     */
    public static final String REQUEST_BINDER_DIRECT = "AxRuntime.REQUEST_BINDER_DIRECT";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
}
