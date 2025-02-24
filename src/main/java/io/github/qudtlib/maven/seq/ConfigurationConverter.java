package io.github.qudtlib.maven.seq;

import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public class ConfigurationConverter {
    public static Xpp3Dom toXpp3Dom(PlexusConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        Xpp3Dom dom = new Xpp3Dom(configuration.getName());
        String value = configuration.getValue(null);
        if (value != null) {
            dom.setValue(value);
        }
        String[] attributeNames = configuration.getAttributeNames();
        if (attributeNames != null) {
            for (String attr : attributeNames) {
                String attrValue = configuration.getAttribute(attr, null);
                if (attrValue != null) {
                    dom.setAttribute(attr, attrValue);
                }
            }
        }
        int childCount = configuration.getChildCount();
        for (int i = 0; i < childCount; i++) {
            PlexusConfiguration child = configuration.getChild(i);
            Xpp3Dom childDom = toXpp3Dom(child);
            dom.addChild(childDom);
        }
        return dom;
    }
}
