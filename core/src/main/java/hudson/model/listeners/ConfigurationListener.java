package hudson.model.listeners;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Hudson;

/**
 * Receives notifications about master configuration changes
 * 
 * @author Stefan Brausch
 */
public class ConfigurationListener implements ExtensionPoint {

	/**
	 * All the registered {@link ConfigurationListener}s.
	 */
	public static ExtensionList<ConfigurationListener> all() {
		return Hudson.getInstance().getExtensionList(
				ConfigurationListener.class);
	}

	/**
	 * Called after configuration is changed.
	 */
	public void onChanged() {
	}
}
