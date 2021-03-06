package com.lauriethefish.betterportals.entitymanipulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.lauriethefish.betterportals.ReflectUtils;
import com.lauriethefish.betterportals.portal.Portal;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import lombok.Getter;

// Stores the current state of a fake entity
@Getter
public class ViewableEntity {
    private EntityManipulator manipulator;

    private Entity entity; // The entity that this fake entity replicates
    private Object nmsEntity;
    private int entityId;
    
    private Portal portal; // Used to find where to entity should appear on this side of the portal
    private EntityEquipmentState equipment; // The equipment that the player current sees on the entity

    // The rotation and location could just be stored together, however this would be annoying when checking for equality
    private Vector location;
    private Vector rotation; // Rotation of the entity in the last tick, used to find if we need to resend the entity look packet
    private byte byteYaw;
    private byte bytePitch;
    private byte byteHeadRotation;
    // Store the location in the previous tick, as this is needed to send relative move packets
    private Vector oldLocation;
    private boolean sleepingLastTick = false;
    private List<Entity> oldPassengers;

    public ViewableEntity(EntityManipulator manipulator, Entity entity, Portal portal, Random random)   {
        this.manipulator = manipulator;
        this.entity = entity;
        this.portal = portal;
        this.nmsEntity = ReflectUtils.runMethod(entity, "getHandle");
        // Generate a random entityId, since otherwise, the real entity with the same ID may be moved instead of the fake one
        this.entityId = random.nextInt(Integer.MAX_VALUE);

        calculateLocation();
        calculateRotation();
        updateEntityEquipment();
    }

    // Returns the change in location if the location changed, otherwise null is returned
    public Vector calculateLocation() {
        oldLocation = location;
        
        location = portal.moveDestinationToOrigin(EntityManipulator.getEntityPosition(entity, nmsEntity));

        return location.equals(oldLocation) || oldLocation == null ? null : location.clone().subtract(oldLocation);
    }

    // Returns true if the rotation changed
    private boolean calculateRotation() {
        Vector oldRotation = rotation;
        rotation = portal.rotateToOrigin(entity.getLocation().getDirection());

        // Dummy location to get the pitch and yaw more easily
        Location loc = entity.getLocation();
        loc.setDirection(rotation);
        byteYaw = (byte) (loc.getYaw() * 256 / 360);
        bytePitch = (byte) (loc.getPitch() * 256 / 360);

        return !rotation.equals(oldRotation);
    }

    // Sends a PacketPlayOutEntityHeadRotation if the entity's head rotation changed
    private void updateHeadRotation() {
        byte oldHeadRotation = byteHeadRotation;
        // Use the methods in Location to easily convert the yaw to a vector and back again
        Location loc = entity.getLocation();
        float headRotation = (float) ReflectUtils.runMethod(nmsEntity, "getHeadRotation");
        loc.setYaw(headRotation);
        loc = loc.setDirection(portal.rotateToOrigin(loc.getDirection()));
        byteHeadRotation = (byte) (loc.getYaw() * 256 / 360);
        
        if(byteHeadRotation != oldHeadRotation) {
            manipulator.sendHeadRotationPacket(entityId, byteHeadRotation);
        }
    }

    // Deals with sending a PacketPlayOutEntityEquipment if the equipment changed
    private void updateEntityEquipment()    {
        // Only LivingEntities have equipment
        if(!(entity instanceof LivingEntity)) {return;}

        LivingEntity livingEntity = (LivingEntity) entity;

        EntityEquipmentState oldEquipment = equipment;
        equipment = new EntityEquipmentState(livingEntity.getEquipment());
        // If the equipment changed, send packets to show it to the player
        if(!equipment.equals(oldEquipment)) {
            manipulator.sendEntityEquipmentPackets(livingEntity, entityId);
        }
    }

    private void updateMountedEntities()    {
        // If our passengers have not changed, then return
        List<Entity> passengers = entity.getPassengers();
        if(passengers.equals(oldPassengers))  {return;}

        // Otherwise, loop through each passenger, and check if it is visible to the player through the portal
        ArrayList<Integer> visiblePassengers = new ArrayList<>();
        for(Entity entity : passengers) {
            ViewableEntity viewed = manipulator.getViewedEntity(entity);
            if(viewed != null)  {
                visiblePassengers.add(viewed.getEntityId());
            }
        }
        // Send a PacketPlayOutMount for the entities that are actually visible
        int[] mountedIds = visiblePassengers.stream().mapToInt(i -> i).toArray();
        if(mountedIds.length > 0)   {
            manipulator.sendMountPacket(entityId, mountedIds);
        }

        oldPassengers = passengers;
    }

    // Called by EntityManipulator, this function should send all the packets necessary to keep the entity replicated
    void update()   {
        // PacketPlayOutEntityMetadata carries the vast majority of entity data
        manipulator.sendMetadataPacket(nmsEntity, entityId);

        // Send PacketPlayOutEntityEquipment if we need to
        updateEntityEquipment();

        // Send packets to update the entity's head rotation if we need to
        updateHeadRotation();

        // Send packets to control which entities are riding this entity
        updateMountedEntities();

        if(entity instanceof HumanEntity) {
            boolean sleeping = ((HumanEntity) entity).isSleeping();

            // If we are now sleeping, and weren't last tick, send the PacketPlayOutBed
            // NOTE: This packet isn't required on some versions
            if(sleeping && !sleepingLastTick && ReflectUtils.sendBedPackets)   {
                manipulator.sendSleepPacket(nmsEntity, entityId);
            }   else if(!sleeping && sleepingLastTick)  {
                // If we are no longer sleeping, then send the animation for exiting a bed
                manipulator.sendAnimationPacket(this, 2);
            }

            sleepingLastTick = sleeping;
        }

        // Don't send movement packets for hanging entities
        if(entity instanceof Hanging)   {return;}

        boolean rotChanged = calculateRotation();
        Vector posOffset = calculateLocation();
        if(posOffset != null)  {
            // If our position changed, and the distance was short enough for relative move packets
            if(manipulator.isSafeForMovePacket(posOffset)) {
                // If our rotation changed as well, send a PacketPlayOutRelEntityMoveLook
                if(rotChanged)  {
                    manipulator.sendMoveLookPacket(entityId, posOffset, byteYaw, bytePitch);
                }   else    {
                    // Otherwise, just send PacketPlayOutRelEntityMove
                    manipulator.sendMovePacket(entityId, posOffset);
                }
            }   else    {
                // Send a teleport packet if the distance was too great. In general this shouldn't happen, but it may
                manipulator.sendTeleportPacket(entityId, location, byteYaw, bytePitch);
            }
        }   else if(rotChanged) {
            // If only the rotation changed and nothing else, send a PacketPlayOutEntityLook
            manipulator.sendLookPacket(entityId, byteYaw, bytePitch);
        }
    }
}