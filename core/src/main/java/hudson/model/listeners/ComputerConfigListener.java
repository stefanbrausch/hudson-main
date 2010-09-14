package hudson.model.listeners;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Hudson;
import hudson.model.Node;

/**
 * Receives notifications about computer and slave changes.
 * 
 * @author Stefan Brausch
 */
public class ComputerConfigListener implements ExtensionPoint {
	/**
	 * Called after a new slave is created and added to {@link Hudson}.
	 */
	public void onCreated(Node node) {
	}

	
	/**
	 * Called right before a slave is going to be deleted.
	 * 
	 * At this point the data files of the slave is already gone.
	 */
	public void onDeleted(Node node) {
	}

	/**
	 * Called after a slave is going offline.
	 */
	public void goesOffline(Node node) {
	}
	
	/**
	 * Called after a slave is going online.
	 */
	public void goesOnline(Node node) {
	}

	/**
	 * All the registered {@link ItemListener}s.
	 */
	public static ExtensionList<ComputerConfigListener> all() {
		return Hudson.getInstance().getExtensionList(ComputerConfigListener.class);
	}

	/**
	 * Called after a slave configuriation is changed.
	 */
	public void onChanged(Node node) {
	}
}
