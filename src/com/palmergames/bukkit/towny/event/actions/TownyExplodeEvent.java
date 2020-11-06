package com.palmergames.bukkit.towny.event.actions;

import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Part of the API which lets Towny's war and other plugins modify Towny's
 * plot-permission-decision outcomes.
 * 
 * Explosion events are thrown when an explosion occurs in a Towny world.
 * 
 * @author LlmDl
 */
public class TownyExplodeEvent extends Event implements Cancellable {

	private static final HandlerList handlers = new HandlerList();
	private final Location location;
	private boolean canExplode;
	private boolean allowBlockDamage;
	private boolean allowEntityDamage;

	public TownyExplodeEvent(Location location, boolean canExplode) {
		this.location = location;
		this.canExplode = canExplode;
		this.allowBlockDamage = canExplode;
		this.allowEntityDamage = canExplode;
	}
	
	public static HandlerList getHandlerList() {
		return handlers;
	}

	public HandlerList getHandlers() {
		return handlers;
	}

	@Override
	public boolean isCancelled() {
		return canExplode;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.canExplode = cancel;
	}

	public Location getLocation() {
		return location;
	}

	public boolean isAllowEntityDamage() {
		return allowEntityDamage;
	}

	public void setAllowEntityDamage(boolean allowEntityDamage) {
		this.allowEntityDamage = allowEntityDamage;
	}

	public boolean isAllowBlockDamage() {
		return allowBlockDamage;
	}

	public void setAllowBlockDamage(boolean allowBlockDamage) {
		this.allowBlockDamage = allowBlockDamage;
	}

}
