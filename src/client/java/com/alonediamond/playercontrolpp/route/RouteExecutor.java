package com.alonediamond.playercontrolpp.route;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class RouteExecutor {

    public enum State {
        IDLE,
        MOVING,
        STUCK_JUMP,
        FAILED,
        COMPLETED
    }

    private static final double STUCK_THRESHOLD_SQ = 0.01; // 0.1 blocks squared
    private static final int STUCK_TICKS = 60;              // 3 seconds
    private static final int STUCK_JUMP_TICKS = 100;        // 5 seconds after jump
    private static final double YAW_CORRECTION_SPEED = 5.0; // degrees per tick
    private static final double YAW_DEAD_ZONE = 2.0;        // allowed deviation in degrees

    private final Route route;
    private State state = State.IDLE;
    private RouteNode currentTarget;
    private int direction = +1;
    private int completedSegments = 0;
    private int totalSegments = 0;
    private int stuckTicks = 0;
    private int postJumpTicks = 0;
    private boolean jumpRequested = false;
    private Vec3d lastPosition = Vec3d.ZERO;

    public RouteExecutor(Route route) {
        this.route = route;
    }

    public Route getRoute() { return route; }
    public State getState() { return state; }
    public int getCompletedSegments() { return completedSegments; }
    public int getTotalSegments() { return totalSegments; }
    public RouteNode getCurrentTarget() { return currentTarget; }

    public boolean isActive() {
        return state == State.MOVING || state == State.STUCK_JUMP;
    }

    public void start() {
        if (route.getNodes().size() < 2) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        state = State.MOVING;
        stuckTicks = 0;
        postJumpTicks = 0;
        jumpRequested = false;
        lastPosition = player.getPos();

        // Determine initial direction and target
        RouteNode startNode = route.getStartPos();
        RouteNode endNode = route.getEndPos();
        double distToStart = player.squaredDistanceTo(startNode.x, startNode.y, startNode.z);
        double distToEnd = player.squaredDistanceTo(endNode.x, endNode.y, endNode.z);

        if (distToStart <= distToEnd) {
            direction = +1;
            currentTarget = endNode;
        } else {
            direction = -1;
            currentTarget = startNode;
        }

        totalSegments = route.getTotalSegments();
        completedSegments = 0;
    }

    public void stop() {
        state = State.IDLE;
        jumpRequested = false;
    }

    public void tick(MinecraftClient client) {
        if (!isActive()) return;

        ClientPlayerEntity player = client.player;
        if (player == null || player.isDead()) {
            state = State.IDLE;
            return;
        }

        // Check dimension
        String currentDim = player.getWorld().getRegistryKey().getValue().toString();
        if (!route.getDimensionId().isEmpty() && !route.getDimensionId().equals(currentDim)) {
            state = State.FAILED;
            return;
        }

        Vec3d currentPos = player.getPos();

        // Check arrival
        double distSq = currentTarget.squaredDistanceTo(currentPos.x, currentPos.y, currentPos.z);
        double arrivalSq = route.getArrivalRadius() * route.getArrivalRadius();

        if (distSq <= arrivalSq) {
            onArrival();
            return;
        }

        // Stuck detection
        double movedSq = currentPos.squaredDistanceTo(lastPosition);
        if (movedSq < STUCK_THRESHOLD_SQ) {
            stuckTicks++;
            if (state == State.MOVING) {
                if (stuckTicks >= STUCK_TICKS) {
                    // First stuck: try jumping
                    state = State.STUCK_JUMP;
                    jumpRequested = true;
                    postJumpTicks = 0;
                    stuckTicks = 0;
                }
            } else if (state == State.STUCK_JUMP) {
                postJumpTicks++;
                if (postJumpTicks >= STUCK_JUMP_TICKS) {
                    state = State.FAILED;
                    return;
                }
            }
        } else {
            // Player moved - reset stuck detection
            if (state == State.STUCK_JUMP) {
                state = State.MOVING;
                postJumpTicks = 0;
            }
            stuckTicks = 0;
            jumpRequested = false;
        }

        lastPosition = currentPos;

        // Yaw control
        if (state == State.MOVING || state == State.STUCK_JUMP) {
            adjustYaw(client, currentTarget);
        }
    }

    private void adjustYaw(MinecraftClient client, RouteNode target) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        float currentYaw = MathHelper.wrapDegrees(player.getYaw());
        float diff = MathHelper.wrapDegrees(desiredYaw - currentYaw);

        // Mixed mode: only auto-correct when deviation exceeds dead zone
        if (Math.abs(diff) < YAW_DEAD_ZONE) return;

        float correction = (float) Math.copySign(
                Math.min(Math.abs(diff), (float) YAW_CORRECTION_SPEED), diff);

        float newYaw = MathHelper.wrapDegrees(currentYaw + correction);
        player.setYaw(newYaw);
        player.setHeadYaw(newYaw);
    }

    private void onArrival() {
        completedSegments++;
        if (totalSegments > 0 && completedSegments >= totalSegments) {
            state = State.COMPLETED;
            return;
        }
        // Swap target
        if (currentTarget == route.getStartPos()) {
            currentTarget = route.getEndPos();
        } else {
            currentTarget = route.getStartPos();
        }
        direction *= -1;
        stuckTicks = 0;
        postJumpTicks = 0;
        jumpRequested = false;
    }

    public boolean needsJump() { return jumpRequested; }

    public void clearJump() { jumpRequested = false; }
}
