package baritone.api.cache;
public interface IWaypoint {
    enum Tag { HOME, USER }
    String getName();
    Tag getTag();
    Object getLocation();
}
