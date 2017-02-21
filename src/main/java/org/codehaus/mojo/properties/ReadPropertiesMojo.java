package org.codehaus.mojo.properties;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file 
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY 
 * KIND, either express or implied.  See the License for the 
 * specific language governing permissions and limitations 
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.cli.CommandLineUtils;

/**
 * The read-project-properties goal reads property files and stores
 * the properties as project properties.  It serves as an alternate
 * to specifying properties in pom.xml.
 *
 * @author <a href="mailto:zarars@gmail.com">Zarar Siddiqi</a>
 * @author <a href="mailto:Krystian.Nowak@gmail.com">Krystian Nowak</a>
 * @version $Id: ReadPropertiesMojo.java 8861 2009-01-21 15:35:38Z pgier $
 * @goal read-project-properties
 */
public class ReadPropertiesMojo extends AbstractMojo
{
	private static final String ACTIVE_PROFILE = "project.activeProfile";
	
    /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * The properties files that will be used when reading properties.
     *
     * @parameter
     * @required
     */
    private File[] files;

    /**
     * If the plugin should be quiet if any of the files was not found
     *
     * @parameter default-value="false"
     */
    private boolean quiet;

    /**
     * If the plugin should be quiet if any of the files was not found
     *
     * @parameter default-value="false"
     */
    private boolean skip;

    /**
     * @parameter default-value="${settings}", required = true, readonly = true
     */
    private Settings settings;
    
    public void execute() throws MojoExecutionException
    {
    	if (skip) {
    		return;
    	}
    	
        Properties projectProperties = new Properties();
        for ( int i = 0; i < files.length; i++ )
        {
            File file = files[i];
            // if a file is a undefined system property it will be null, ignore this.
            if (file != null) {
	            if (file != null && file.getAbsolutePath().contains("${" + ACTIVE_PROFILE + "}")) 
	            {
	            	getLog().info("Expanding property file: " + file);
	            	
	            	for ( String profileId: settings.getActiveProfiles() )  // this includes the -P profiles specified
	                {
	            		getLog().info("Profile: " + profileId);
	                    File expandedFile = new File(file.getAbsolutePath().replace("${" + ACTIVE_PROFILE + "}", profileId));
	                    
	                    if (expandedFile.exists()) {
	                    	getLog().info("Expanded file path: " + expandedFile);
	                    	file = expandedFile;
	                    	break;
	                    }
	                }
	            }
	            
	            if ( file.exists() )
	            {
	                try
	                {
	                    getLog().debug( "Loading property file: " + file );
	                    
	                    FileInputStream stream = new FileInputStream( file );
	                    projectProperties = project.getProperties();
	                    
	                    try
	                    {
	                        projectProperties.load( stream );
	                    }
	                    finally
	                    {
	                        if ( stream != null )
	                        {
	                            stream.close();
	                        }
	                    }
	                }
	                catch ( IOException e )
	                {
	                	if ( !quiet ) {
	                		throw new MojoExecutionException( "Error reading properties file " + file.getAbsolutePath(), e );
	                	}
	                }	
	            }
	            else
	            {
	                if ( quiet )
	                {
	                    getLog().warn( "Ignoring missing properties file: " + file.getAbsolutePath() );
	                }
	                else
	                {
	                    throw new MojoExecutionException( "Properties file not found: " + file.getAbsolutePath() );
	                }
	            }
            }
        }

        boolean useEnvVariables = false;
        for ( Enumeration n = projectProperties.propertyNames(); n.hasMoreElements(); )
        {
            String k = (String) n.nextElement();
            String p = (String) projectProperties.get( k );
            if ( p.indexOf( "${env." ) != -1 )
            {
                useEnvVariables = true;
                break;
            }
        }
        Properties environment = null;
        if ( useEnvVariables )
        {
            try
            {
                environment = CommandLineUtils.getSystemEnvVars();
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "Error getting system envorinment variables: ", e );
            }
        }
        
        for ( Enumeration n = projectProperties.propertyNames(); n.hasMoreElements(); )
        {
            String k = (String) n.nextElement();
            projectProperties.setProperty( k, getPropertyValue( k, projectProperties, environment ) );
        }
    }
    	
    /**
     * Retrieves a property value, replacing values like ${token}
     * using the Properties to look them up.
     * Shamelessly adapted from:
     * http://maven.apache.org/plugins/maven-war-plugin/xref/org/apache/maven/plugin/war/PropertyUtils.html
     *
     * It will leave unresolved properties alone, trying for System
     * properties, and environment variables and implements reparsing
     * (in the case that the value of a property contains a key), and will
     * not loop endlessly on a pair like test = ${test}
     *
     * @param k           property key
     * @param p           project properties
     * @param environment environment variables
     * @return resolved property value
     */
    private String getPropertyValue( String k, Properties p, Properties environment )
    {
        String v = p.getProperty( k );
        String ret = "";
        int idx, idx2;

        while ( ( idx = v.indexOf( "${" ) ) >= 0 )
        {
            // append prefix to result
            ret += v.substring( 0, idx );

            // strip prefix from original
            v = v.substring( idx + 2 );

            idx2 = v.indexOf( "}" );
            
            // if no matching } then bail
            if ( idx2 < 0 )
            {
                break;
            }

            // strip out the key and resolve it
            // resolve the key/value for the ${statement}
            String nk = v.substring( 0, idx2 );
            v = v.substring( idx2 + 1 );
            String nv = p.getProperty( nk );

            // try global environment
            if ( nv == null )
            {
                nv = System.getProperty( nk );
            }

            // try environment variable
            if ( nv == null && nk.startsWith( "env." ) && environment != null )
            {
                nv = environment.getProperty( nk.substring( 4 ) );
            }

            // if the key cannot be resolved,
            // leave it alone ( and don't parse again )
            // else prefix the original string with the
            // resolved property ( so it can be parsed further )
            // taking recursion into account.
            if ( nv == null || nv.equals( nk ) )
            {
                ret += "${" + nk + "}";
            }
            else
            {
                v = nv + v;
            }
        }
        return ret + v;
    }
}
