package com.lauriethefish.betterportals.portal;

import java.util.Set;

import com.lauriethefish.betterportals.BetterPortals;
import com.lauriethefish.betterportals.ReflectUtils;
import com.lauriethefish.betterportals.WorldLink;
import com.lauriethefish.betterportals.multiblockchange.ChunkCoordIntPair;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.util.Vector;

// Handles finding a suitable location for spawning a portal, can also deal with the actual building of the portal
public class PortalSpawnSystem {
    private BetterPortals pl;

    public PortalSpawnSystem(BetterPortals pl)  {
        this.pl = pl;
    }

    // Limits the coordinates so that they are not outside the allowed are in the given world
    public void maxCoordinatesInsideWorld(Location destLoc, WorldLink link, Vector realPSize, PortalDirection direction) {
        // Limit the Y by the correct values
        destLoc.setY(Math.min(link.maxSpawnY - realPSize.getY(), destLoc.getY()));
        destLoc.setY(Math.max(link.minSpawnY, destLoc.getY()));

        // Get the world's border
        WorldBorder worldBorder = destLoc.getWorld().getWorldBorder();
        // Find half of the diameter to get the radius, then subtract a small amount so that portals don't spawn on the very edge of the border
        double wbRadius = (worldBorder.getSize() / 2.0) - 3.0;
        Vector wbCenter = worldBorder.getCenter().toVector();
        Vector lowXZLimit = wbCenter.clone().subtract(new Vector(wbRadius, 0.0, wbRadius));
        Vector highXZLimit = wbCenter.clone().add(new Vector(wbRadius, 0.0, wbRadius)).subtract(direction.swapVector(realPSize));

        // Limit the X and Z values by the world border
        destLoc.setX(Math.min(destLoc.getX(), highXZLimit.getX())); destLoc.setX(Math.max(destLoc.getX(), lowXZLimit.getX()));
        destLoc.setZ(Math.min(destLoc.getZ(), highXZLimit.getZ())); destLoc.setZ(Math.max(destLoc.getZ(), lowXZLimit.getZ()));
    }

    // Find a suitable location for spawning the portal
    // If a suitable location cannot be found, it just returns the location given
    // A suitable location is defined as one where the bottom of the portal is solid blocks,
    // and the three blocks above are all in air
    // The location returned is the bottom left block of the portal
    // This function will try to find and link to use for scaling the coordinates,
    // if no link is found it will return null
    // The portalSize should be on the x and y coordinates, even if the portal is oriented along the z, it is the size of the portal window, not including the obsidian
    public Location findSuitablePortalLocation(Location originLocation, PortalDirection direction, Vector portalSize) {
        // Loop through all of the links between worlds, and try to find a link for this portal
        WorldLink link = null;
        for(WorldLink currentLink : pl.config.worldLinks)   {
            if(currentLink.originWorld.equals(originLocation.getWorld())) {
                link = currentLink;
                break;
            }
        }
        // If no link was found, return null
        if(link == null)    {
            return null;
        }

        // Multiply by the links rescaling factor and then set it to the correct world
        Location destinationLoc = originLocation.clone();
        destinationLoc.multiply(link.coordinateRescalingFactor);
        // Reset the Y coordinate since it should not be effected by coordinate rescaling
        destinationLoc.setY(originLocation.getY());
        destinationLoc.setWorld(link.destinationWorld);

        // Get the actual side of the portal, not just the size of the area of portal blocks
        Vector realPSize = portalSize.clone().add(new Vector(2.0, 2.0, 0.0));
        
        // Make sure that the portal does not spawn outside the world
        maxCoordinatesInsideWorld(destinationLoc, link, realPSize, direction);
        WorldBorder border = link.destinationWorld.getWorldBorder();
        
        // Convert the location to a block and back, this should floor the location so it is all whole numbers
        Location prefferedLocation = destinationLoc.getBlock().getLocation();

        Location a = prefferedLocation.clone().subtract(128, 0, 128);
        Location b = prefferedLocation.clone().add(128, 0, 128);
        Set<ChunkCoordIntPair> chunks = ChunkCoordIntPair.findArea(a, b);

        // Loop through each chunk around the portal to search for existing portals
        Location closestExistingPortal = null;
        for(ChunkCoordIntPair chunkPos : chunks)  {
            // Only check for existing portals in chunks that have already been generated
            if(chunkPos.isGenerated())  {
                closestExistingPortal = checkForExistingFrameInChunk(prefferedLocation, closestExistingPortal, chunkPos, direction, portalSize);
                chunkPos.getChunk().unload();
            }
        }

        if(closestExistingPortal != null)   {
            return closestExistingPortal;
        }

        // Variables for storing the current closestSuitableLocation
        Location closestSuitableLocation = null;
        // Set the current closest distance to infinity so that all numbers are less than it
        double closestSuitableDistance = Double.POSITIVE_INFINITY;

        // Loop through a few areas around the portal
        for(double z = -128.0; z < 128.0; z += 1.0)  {
            for(double y = link.minSpawnY; y <= link.maxSpawnY; y++) {
                for(double x = -128.0; x < 128.0; x += 1.0)  {
                    // Find the location of the current potential portal spawn
                    Location newLoc = new Location(prefferedLocation.getWorld(), prefferedLocation.getX() + x, y, prefferedLocation.getZ() + z);

                    // Check that both corners of the portal are inside the world border
                    if(!border.isInside(newLoc) || !border.isInside(newLoc.clone().add(realPSize)))    {
                        continue;
                    }

                    // Check if the distance is less than the closest distance first, for performance
                    double distance = prefferedLocation.distance(newLoc);
                    boolean isCloser = distance < closestSuitableDistance;

                    // If the current closest portal is less suitable that this portal, or is equally as suitable AND is closer, then overrite the closest portal
                    if(isCloser)    {
                        boolean suitable = checkSuitableSpawnLocation(newLoc.clone(), direction, portalSize);
                        if(suitable)    {
                            closestSuitableLocation = newLoc;
                            closestSuitableDistance = distance;
                        }
                    }
                }  
            }
        }

        // If a suitable location was found, return it
        if(closestSuitableLocation != null) {
            return closestSuitableLocation;
        }
        
        // Otherwise return the given location
        return prefferedLocation;
    }

    // Checks if the position is given is suitable for spawning a portal
    // See definition of a suitable location above
    public boolean checkSuitableSpawnLocation(Location location, PortalDirection direction, Vector portalSize) {
        // Loop through the two colums of portal blocks
        for(double x = 0.0; x <= portalSize.getX() + 1.0; x++)  {
            Location currentPosX = location.clone().add(direction.swapVector(new Vector(x, 0.0, 0.0)));
        
            Material groundType = currentPosX.getBlock().getType();
            // If the ground is not solid, it is not suitable 
            if(!groundType.isSolid())  {
                return false;
            }

            // If the air above the columns is solid or lava/water then it is not suitable
            for(double y = 1.0; y <= portalSize.getY() + 1.0; y++)    {
                // Get our current position including the y
                Block currentBlock = currentPosX.clone().add(0.0, y, 0.0).getBlock();
                Material type = currentBlock.getType();

                if(type.isSolid() || currentBlock.isLiquid())   {
                    return false;
                }
            }
        }

        if(checkPortalProximity(location))  {return false;}
        return true;
    }

    // Returns true if this position is too close to another portal to be used as a spawn location
    private boolean checkPortalProximity(Location loc)  {
        // Loop through each portal
        for(Portal portal : pl.getPortals())    {
            Location otherPos = portal.getOriginPos();

            // If the portal is in the same world, and is too close, return true
            if(otherPos.getWorld() == loc.getWorld() && otherPos.distance(loc) < pl.config.minimumPortalSpawnDistance) {
                return true;
            }
        }
        return false;
    }

    // Checks all four corners of the portal given to see if they are solid blocks
    // If they are not, they are set to stone
    public void fixPortalCorners(Location location, PortalDirection direction, Vector portalSize)  {
        Vector[] portalCornerLocations = {
            new Vector(0.0, 0.0, 0.0),
            new Vector(portalSize.getX() + 1.0, 0.0, 0.0),
            new Vector(0.0, portalSize.getY() + 1.0, 0.0),
            new Vector(portalSize.getX() + 1.0, portalSize.getY() + 1.0, 0.0),
        };

        // Loop through all four corners
        for(Vector offset : portalCornerLocations)  {
            // If the portal is facing north/south, invert the x and z coordinates
            offset = direction.swapVector(offset);

            // Find the location of the block
            Location newLoc = location.clone().add(offset);
            // If the block isn't fully occluding, set it to stone
            if(!newLoc.getBlock().getType().isOccluding())  {
                newLoc.getBlock().setType(Material.STONE);
            }
        }
    }

    // Checks to see if there is an existing portal frame in the given location
    // If the frame has more than 6 blocks missing, or there is a non-portal block inside the portal area, this returns false
    public boolean checkForExistingFrame(Location location, PortalDirection direction, Vector portalSize)   {
        int wrongBlocks = 0;

        // Loop through each block of the portal
        Vector realSize = portalSize.add(new Vector(1.0, 1.0, 0.0));
        for(double y = 0.0; y <= realSize.getY(); y++)  {
            for(double x = 0.0; x <= realSize.getX(); x++)  {
                // Find the position at this block, then find the block type
                Location blockPos = location.clone().add(direction.swapVector(new Vector(x, y, 0.0)));
                Material type = blockPos.getBlock().getType();

                // Check to see if this block should be one of the obsidian blocks on the edge of the frame
                if(x == 0 || x == realSize.getX() || y == 0.0 || y == realSize.getY())  {
                    // If the block isn't obsidian when it should be, increment wrongBlocks
                    if(!(type == Material.OBSIDIAN))    {
                        wrongBlocks++;
                    }
                }   else    {
                    // Otherwise, return false if there was a non air or portal block in the portal window
                    if(!(type == Material.AIR || type == ReflectUtils.portalMaterial))    {
                        return false;
                    }
                }

                if(wrongBlocks > 6) {return false;}
            }
        }

        // Return true if there are no more than 6 missing obsidian blocks
        return true;
    }

    public Location checkForExistingFrameInChunk(Location prefferedPos, Location currentClosest, ChunkCoordIntPair chunkPos, PortalDirection direction, Vector portalSize) {
        Location chunkBottomLeft = chunkPos.getBottomLeft();

        double closestDistance = currentClosest == null ? Double.POSITIVE_INFINITY : currentClosest.distance(prefferedPos);
        // Limit our Y coordinate so that we don't check areas above Y 255
        int maxY = 254 - portalSize.getBlockY();
        // Loop through each block of the chunk
        for(int x = 0; x < 16; x++) {
            for(int z = 0; z < 16; z++)    {
                for(int y = 0; y < maxY; y++)    {
                    Location checkPos = chunkBottomLeft.clone().add(x, y, z);

                    // Find if this location is any closer than our current closest point
                    double distance = prefferedPos.distance(checkPos);
                    if(currentClosest == null || distance < closestDistance)  {
                        // Check if the block one to the right of the portal is obsidian before continuing.
                        // This ends up being quite a large optimization, since it rules out most areas that aren't portals
                        Location blockPos = checkPos.clone().add(direction.swapVector(new Vector(1.0, 0.0, 0.0)));
                        if(blockPos.getBlock().getType() != Material.OBSIDIAN)  {
                            continue;
                        }

                        // Check to see if there is an existing portal frame
                        if(checkForExistingFrame(checkPos, direction, portalSize.clone()) && !checkPortalProximity(checkPos)) {
                            currentClosest = checkPos;
                            closestDistance = distance;
                        }
                    }
                }
            }
        }

        return currentClosest;
    }

    // Spawns a portal at the given location, with the correct orientation
    // Also spawns four blocks at the sides of the portal to stand on if they are not solid
    @SuppressWarnings("deprecation")
    public void spawnPortal(Location location, PortalDirection direction, Vector portalSize)  {
        // Loop through the x, y and z coordinates around the portal
        // Portal is only generated at z == 0,
        // The other two z coordinates are used for generating the blocks at the sides of portals
        for(double z = -1.0; z <= 1.0; z++) {
            for(double y = 0.0; y <= portalSize.getY() + 1.0; y++)  {
                for(double x = 0.0; x <= portalSize.getX() + 1.0; x++)  {
                    // Calculate the location next to the portal
                    Location newLoc = location.clone().add(direction.swapVector(new Vector(x, y, z)));

                    // If the z is not 0, generate the blocks at the sides of the portal
                    if(z != 0.0)    {
                        // If the y is 0 and the x and 1 or 2 then the ground blocks need
                        // to be put down
                        if(y == 0.0)    {
                            if(x <= portalSize.getX() && x != 0.0)    {
                                // Only update the ground blocks if they are not solid
                                if(!newLoc.getBlock().getType().isSolid()) {
                                    newLoc.getBlock().setType(Material.OBSIDIAN);
                                }
                            }
                        }   else    {
                            newLoc.getBlock().setType(Material.AIR);
                        }
                        
                        continue;
                    }

                    // Otherwise, generate the portal blocks
                    // Note that we do NOT update physics when generating these blocks.
                    // This is because the physics deletes portal blocks that shouldn't be there
                    BlockState state = newLoc.getBlock().getState();
                    if(x == 0 || x == portalSize.getX() + 1.0 || y == 0.0 || y == portalSize.getY() + 1.0)  {
                        // Set the sides of the portal to obsidian
                        state.setType(Material.OBSIDIAN);
                    }   else    {
                        // Set the centre to portal. NOTE: The portal must be rotated if the portal faces north/south
                        // Rotating is done using the manual block data byte method, since this works on all version AFAIK
                        state.setType(ReflectUtils.portalMaterial);
                        if(direction == PortalDirection.EAST || direction == PortalDirection.WEST)    {
                            state.setRawData((byte) 2);
                        }
                    }
                    state.update(true, false);  // Disable physics so that portals don't get broken
                }
            }
        }
    }
}