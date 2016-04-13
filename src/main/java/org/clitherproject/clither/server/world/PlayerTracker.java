package org.clitherproject.clither.server.world;

import com.google.common.collect.ImmutableList;
import org.clitherproject.clither.server.ClitherServer;
import org.clitherproject.clither.server.entity.EntityImpl;
import org.clitherproject.clither.server.net.PlayerConnection;
// import org.clitherproject.clither.server.net.packet.outbound.PacketOutUpdateNodes;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.clitherproject.clither.api.entity.Snake;

public class PlayerTracker {

    private final PlayerImpl player;
    private final PlayerConnection conn;
    private final WorldImpl world;
    private final Set<Integer> visibleEntities = new HashSet<>();
    private final ArrayDeque<EntityImpl> removalQueue = new ArrayDeque<>();
    private double rangeX;
    private double rangeY;
    private double centerX;
    private double centerY;
    private double viewLeft;
    private double viewRight;
    private double viewTop;
    private double viewBottom;
    private long lastViewUpdateTick = 0L;

    public PlayerTracker(PlayerImpl player) {
        this.player = player;
        this.conn = player.getConnection();
        this.world = ClitherServer.getInstance().getWorld();
    }

    public void remove(EntityImpl entity) {
        if (!removalQueue.contains(entity)) {
            removalQueue.add(entity);
        }
    }

    private void updateRange() {
        double totalSize = 1.0D;
        for (Snake snake : player.getSnakes()) {
            totalSize += snake.getPhysicalSize();
        }

        double factor = Math.pow(Math.min(64.0D / totalSize, 1), 0.4D);
        rangeX = world.getView().getBaseX() / factor;
        rangeY = world.getView().getBaseY() / factor;
    }

    private void updateCenter() {
        if (player.getSnakes().isEmpty()) {
            return;
        }

        int size = player.getSnakes().size();
        double x = 0;
        double y = 0;

        for (Snake snake : player.getSnakes()) {
            x += snake.getPosition().getX();
            y += snake.getPosition().getY();
        }

        this.centerX = x / size;
        this.centerY = y / size;
    }

    public void updateView() {
        updateRange();
        updateCenter();

        viewTop = centerY - rangeY;
        viewBottom = centerY + rangeY;
        viewLeft = centerX - rangeX;
        viewRight = centerX + rangeX;

        lastViewUpdateTick = world.getServer().getTick();
    }

    private List<Integer> calculateEntitiesInView() {
        return world
                .getEntities()
                .stream()
                .filter((e) -> e.getPosition().getY() <= viewBottom && e.getPosition().getY() >= viewTop && e.getPosition().getX() <= viewRight
                        && e.getPosition().getX() >= viewLeft).mapToInt((e) -> e.getID()).boxed().collect(Collectors.toList());
    }

    public List<Integer> getVisibleEntities() {
        return ImmutableList.copyOf(visibleEntities);
    }

    public void updateNodes() {
        // Process the removal queue
        Set<Integer> updates = new HashSet<>();
        Set<EntityImpl> removals = new HashSet<>();
        synchronized (removalQueue) {
            removals.addAll(removalQueue);
            removalQueue.clear();
        }

        // Update the view, if needed
        if (world.getServer().getTick() - lastViewUpdateTick >= 5) {
            updateView();

            // Get the new list of entities in view
            List<Integer> newVisible = calculateEntitiesInView();

            synchronized (visibleEntities) {
                // Destroy now-invisible entities
                for (Iterator<Integer> it = visibleEntities.iterator(); it.hasNext();) {
                    int id = it.next();
                    if (!newVisible.contains(id)) {
                        // Remove from player's screen
                        it.remove();
                        removals.add(world.getEntity(id));
                    }
                }

                // Add new entities to the client's screen
                for (int id : newVisible) {
                    if (!visibleEntities.contains(id)) {
                        visibleEntities.add(id);
                        updates.add(id);
                    }
                }
            }
        }

        // Update entities that need to be updated
        for (Iterator<Integer> it = visibleEntities.iterator(); it.hasNext();) {
            int id = it.next();
            EntityImpl entity = world.getEntity(id);
            if (entity == null) {
                // Prune invalid entity from the list
                it.remove();
                continue;
            }

            if (entity.shouldUpdate()) {
                updates.add(id);
            }
        }

        // conn.sendPacket(new PacketOutUpdateNodes(world, removals, updates));
    }
}