package baritone.api.event.listener;
public interface IGameEventListener {
    void onTick(Object event);
    void onPathEvent(Object event);
    boolean onSomethingBoolean();
    int onSomethingInt();
}
