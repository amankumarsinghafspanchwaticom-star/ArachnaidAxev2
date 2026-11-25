package com.arachnaid.manager;

import com.arachnaid.ArachnaidPlugin;
import com.arachnaid.item.ArachnaidItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WallClimbManager {

    public enum StopReason {
        JUMP_OFF,
        SNEAK_DROP,
        TOGGLED_OFF,
        MANTLED,
        LANDED,
        LEFT_WALL,
        OTHER
    }

    private static final BlockFace[] CARDINALS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final ArachnaidPlugin plugin;
    private final ArachnaidItemFactory itemFactory;
    private final NamespacedKey zeroGravityKey;
    private final NamespacedKey moveLockKey;
    private final Map<UUID, ClimbSession> activeSessions = new ConcurrentHashMap<>();
    private BukkitTask climbTask;

    public WallClimbManager(ArachnaidPlugin plugin, ArachnaidItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.zeroGravityKey = new NamespacedKey(plugin, "wall_climb_zero_gravity");
        this.moveLockKey = new NamespacedKey(plugin, "wall_climb_move_lock");
    }

    public void startTask() {
        climbTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public boolean isClimbing(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    /**
     * Enters crawling wall-cling mode on the given clicked wall face.
     * hitFace is the face of the block the player clicked (pointing outward toward the player).
     */
    public void startClimbing(Player player, BlockFace hitFace) {
        UUID id = player.getUniqueId();
        if (activeSessions.containsKey(id)) {
            return;
        }

        BlockFace intoWall = (hitFace != null) ? hitFace.getOppositeFace() : findNearestWallDirection(player, 1.0);
        if (intoWall == null) {
            return;
        }

        Input initialInput = player.getCurrentInput();
        boolean initJump = initialInput != null && initialInput.isJump();
        boolean initSneak = initialInput != null && initialInput.isSneak();

        activeSessions.put(id, new ClimbSession(intoWall, System.currentTimeMillis(), initJump, initSneak));

        applyWallPhysicsModifiers(player);

        if (plugin.getConfig().getBoolean("wall-climb.crawl-pose", true)) {
            player.setSwimming(true);
        }

        // Instant dead stop when latching onto wall — zero elevator drift
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0f);

        player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.7f, 1.35f);
        player.sendActionBar(plugin.rawMsg("climb-start"));
    }

    public void handleInputUpdate(Player player, Input input) {
        ClimbSession session = activeSessions.get(player.getUniqueId());
        if (session == null || input == null) {
            return;
        }

        long graceMs = plugin.getConfig().getLong("wall-climb.cancel-grace-ms", 180L);
        boolean pastGrace = (System.currentTimeMillis() - session.startTimeMs()) >= graceMs;

        boolean jumpNow = input.isJump();
        boolean sneakNow = input.isSneak();

        boolean jumpPressed = jumpNow && !session.prevJump();
        boolean sneakPressed = sneakNow && !session.prevSneak();

        session.setPrevJump(jumpNow);
        session.setPrevSneak(sneakNow);

        if (!pastGrace) {
            return;
        }

        // Space = crisp jump kick off the wall
        if (jumpPressed) {
            stopClimbing(player, StopReason.JUMP_OFF);
            return;
        }

        // Shift = instant straight drop off the wall with zero pushback
        if (sneakPressed) {
            stopClimbing(player, StopReason.SNEAK_DROP);
        }
    }

    public void stopClimbing(Player player, StopReason reason) {
        ClimbSession session = activeSessions.remove(player.getUniqueId());
        cleanupWallPhysicsModifiers(player);

        if (session == null) {
            return;
        }

        player.setSwimming(false);
        player.setFallDistance(0f);

        BlockFace intoWall = session.intoWall();

        switch (reason) {
            case JUMP_OFF -> {
                double push = plugin.getConfig().getDouble("wall-climb.jump-off-push", 0.34);
                double up = plugin.getConfig().getDouble("wall-climb.jump-off-upward", 0.36);
                Vector away = new Vector(-intoWall.getModX() * push, up, -intoWall.getModZ() * push);
                player.setVelocity(away);
                player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_HURT, 0.55f, 1.45f);
                player.sendActionBar(plugin.rawMsg("climb-cancelled"));
            }
            case SNEAK_DROP, TOGGLED_OFF -> {
                player.setVelocity(new Vector(0, -0.04, 0));
                player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_STEP, 0.5f, 0.9f);
                player.sendActionBar(plugin.rawMsg("climb-cancelled"));
            }
            case MANTLED -> {
                Vector vault = new Vector(intoWall.getModX() * 0.24, 0.38, intoWall.getModZ() * 0.24);
                player.setVelocity(vault);
                player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_STEP, 0.6f, 1.4f);
                player.sendActionBar(plugin.rawMsg("climb-stop"));
            }
            case LANDED, LEFT_WALL, OTHER -> {
                if (player.isOnline()) {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.sendActionBar(plugin.rawMsg("climb-stop"));
                }
            }
        }
    }

    public void cleanupWallPhysicsModifiers(Player player) {
        if (player == null) {
            return;
        }
        AttributeInstance gravity = player.getAttribute(Attribute.GRAVITY);
        if (gravity != null) {
            gravity.removeModifier(zeroGravityKey);
        }
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(moveLockKey);
        }
    }

    public void stopAll() {
        if (climbTask != null) {
            climbTask.cancel();
            climbTask = null;
        }
        for (UUID id : activeSessions.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                stopClimbing(player, StopReason.OTHER);
            }
        }
        activeSessions.clear();
    }

    private void applyWallPhysicsModifiers(Player player) {
        cleanupWallPhysicsModifiers(player);

        AttributeInstance gravity = player.getAttribute(Attribute.GRAVITY);
        if (gravity != null) {
            gravity.addTransientModifier(new AttributeModifier(
                    zeroGravityKey,
                    -1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                    EquipmentSlotGroup.ANY
            ));
        }

        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.addTransientModifier(new AttributeModifier(
                    moveLockKey,
                    -1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                    EquipmentSlotGroup.ANY
            ));
        }
    }

    private void tick() {
        if (activeSessions.isEmpty()) {
            return;
        }

        double attachDist = plugin.getConfig().getDouble("wall-climb.wall-attach-distance", 0.95);
        double upSpeed = plugin.getConfig().getDouble("wall-climb.climb-up-speed", 0.22);
        double downSpeed = plugin.getConfig().getDouble("wall-climb.climb-down-speed", 0.22);
        double strafeSpeed = plugin.getConfig().getDouble("wall-climb.strafe-speed", 0.19);
        double hugForce = plugin.getConfig().getDouble("wall-climb.wall-hug-force", 0.06);
        boolean crawlPose = plugin.getConfig().getBoolean("wall-climb.crawl-pose", true);

        Iterator<Map.Entry<UUID, ClimbSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ClimbSession> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            ClimbSession session = entry.getValue();

            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }

            if (!itemFactory.isArachnaidAxe(player.getInventory().getItemInMainHand())) {
                stopClimbing(player, StopReason.OTHER);
                continue;
            }

            Input input = player.getCurrentInput();
            boolean w = input != null && input.isForward();
            boolean s = input != null && input.isBackward();
            boolean a = input != null && input.isLeft();
            boolean d = input != null && input.isRight();

            BlockFace intoWall = session.intoWall();

            // Check if still touching the attached wall
            if (!hasSolidWallInDirection(player, intoWall, attachDist)) {
                // Smoothly vault over the top edge if player is pressing W and ledge is right at feet
                if (w && hasLedgeAtFeet(player, intoWall, attachDist)) {
                    stopClimbing(player, StopReason.MANTLED);
                    continue;
                }

                // Seamlessly pivot around inside corners onto an adjacent wall
                BlockFace alternate = findNearestWallDirection(player, attachDist);
                if (alternate != null) {
                    session.setIntoWall(alternate);
                    intoWall = alternate;
                } else {
                    stopClimbing(player, StopReason.LEFT_WALL);
                    continue;
                }
            }

            if (crawlPose && !player.isSwimming()) {
                player.setSwimming(true);
            }

            int vertical = (w ? 1 : 0) - (s ? 1 : 0); // W = Up (+1), S = Down (-1)
            int strafe = (d ? 1 : 0) - (a ? 1 : 0);   // D = Right (+1), A = Left (-1)

            // If pressing S down onto solid ground, detach cleanly onto the floor
            if (vertical < 0 && isTouchingFloor(player)) {
                stopClimbing(player, StopReason.LANDED);
                continue;
            }

            player.setFallDistance(0f);

            // NO KEYS PRESSED -> INSTANT DEAD STOP ON WALL (ZERO DRIFT, ZERO ELEVATOR)
            if (vertical == 0 && strafe == 0) {
                player.setVelocity(new Vector(0, 0, 0));
                continue;
            }

            // Compute horizontal wall tangent (Right when facing intoWall)
            double rightX = -intoWall.getModZ();
            double rightZ = intoWall.getModX();

            // If player looks away from the wall, flip tangent so A/D always match screen Left/Right
            Vector look = player.getLocation().getDirection();
            double facingDot = look.getX() * intoWall.getModX() + look.getZ() * intoWall.getModZ();
            if (facingDot < -0.25) {
                rightX = -rightX;
                rightZ = -rightZ;
            }

            double vy = (vertical > 0) ? upSpeed : (vertical < 0 ? -downSpeed : 0.0);
            double vx = rightX * strafe * strafeSpeed;
            double vz = rightZ * strafe * strafeSpeed;

            if (vertical != 0 && strafe != 0) {
                vx *= 0.78;
                vy *= 0.78;
                vz *= 0.78;
            }

            // Gentle inward hug counters Minecraft's airborne S back-pedal drift so S climbs straight down
            vx += intoWall.getModX() * hugForce;
            vz += intoWall.getModZ() * hugForce;

            player.setVelocity(new Vector(vx, vy, vz));

            session.incrementMoveTicks();
            if (session.moveTicks() % 7 == 0) {
                Location loc = player.getLocation();
                World world = player.getWorld();
                world.playSound(loc, Sound.ENTITY_SPIDER_STEP, 0.35f, 1.3f);
                world.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.4, 0), 4,
                        0.18, 0.18, 0.18, Material.COBWEB.createBlockData());
            }
        }
    }

    private boolean hasSolidWallInDirection(Player player, BlockFace dir, double distance) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null || dir == null) {
            return false;
        }
        double[] yOffsets = {0.15, 0.75};
        for (double yOff : yOffsets) {
            Block block = world.getBlockAt(
                    Location.locToBlock(base.getX() + dir.getModX() * distance),
                    Location.locToBlock(base.getY() + yOff),
                    Location.locToBlock(base.getZ() + dir.getModZ() * distance)
            );
            if (isClimbableWallBlock(block)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLedgeAtFeet(Player player, BlockFace dir, double distance) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null || dir == null) {
            return false;
        }
        Block ledge = world.getBlockAt(
                Location.locToBlock(base.getX() + dir.getModX() * distance),
                Location.locToBlock(base.getY() - 0.35),
                Location.locToBlock(base.getZ() + dir.getModZ() * distance)
        );
        Block aboveLedge = ledge.getRelative(BlockFace.UP);
        return isClimbableWallBlock(ledge) && !aboveLedge.getType().isSolid();
    }

    private boolean isTouchingFloor(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        Block below = world.getBlockAt(
                Location.locToBlock(loc.getX()),
                Location.locToBlock(loc.getY() - 0.15),
                Location.locToBlock(loc.getZ())
        );
        return below.getType().isSolid() && below.getType() != Material.COBWEB;
    }

    private BlockFace findNearestWallDirection(Player player, double distance) {
        for (BlockFace face : CARDINALS) {
            if (hasSolidWallInDirection(player, face, distance)) {
                return face;
            }
        }
        return null;
    }

    private boolean isClimbableWallBlock(Block block) {
        return block != null && block.getType().isSolid() && block.getType() != Material.COBWEB;
    }

    private static final class ClimbSession {
        private BlockFace intoWall;
        private final long startTimeMs;
        private boolean prevJump;
        private boolean prevSneak;
        private int moveTicks = 0;

        private ClimbSession(BlockFace intoWall, long startTimeMs, boolean prevJump, boolean prevSneak) {
            this.intoWall = intoWall;
            this.startTimeMs = startTimeMs;
            this.prevJump = prevJump;
            this.prevSneak = prevSneak;
        }

        BlockFace intoWall() {
            return intoWall;
        }

        void setIntoWall(BlockFace intoWall) {
            this.intoWall = intoWall;
        }

        long startTimeMs() {
            return startTimeMs;
        }

        boolean prevJump() {
            return prevJump;
        }

        void setPrevJump(boolean prevJump) {
            this.prevJump = prevJump;
        }

        boolean prevSneak() {
            return prevSneak;
        }

        void setPrevSneak(boolean prevSneak) {
            this.prevSneak = prevSneak;
        }

        int moveTicks() {
            return moveTicks;
        }

        void incrementMoveTicks() {
            moveTicks++;
        }
    }
}
