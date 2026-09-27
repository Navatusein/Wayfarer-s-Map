package WayFarMap.client.waypoint;

/** A named set of waypoints that can be shown or hidden together. */
public class WaypointGroup {

    public String name;
    public boolean visible = true;

    public WaypointGroup() {}

    public WaypointGroup(String name) {
        this.name = name;
    }
}
