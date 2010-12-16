/*
 * Copyright 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aws.maven;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.proxy.ProxyInfoProvider;
import org.apache.maven.wagon.repository.Repository;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * An implementation of the Maven Wagon interface that allows you to access the
 * Amazon S3 service. URLs that reference the S3 service should be in the form
 * of <code>s3://bucket.name</code>. As an example
 * <code>s3://static.springframework.org</code> would put files into the
 * <code>static.springframework.org</code> bucket on the S3 service. <p/> This
 * implementation uses the <code>username</code> and <code>passphrase</code>
 * portions of the server authentication metadata for credentials.
 * 
 * @author Ben Hale
 */
public class SimpleStorageServiceWagon extends AbstractWagon {

	private S3Service service;

	private S3Bucket bucket;

	private String basedir;

	public SimpleStorageServiceWagon() {
		super(false);
	}

	protected void connectToRepository(Repository source, AuthenticationInfo authenticationInfo, ProxyInfoProvider proxyInfoProvider)
			throws AuthenticationException {
		try {
			service = new RestS3Service(getCredentials(authenticationInfo));
		}
		catch (S3ServiceException e) {
			throw new AuthenticationException("Cannot authenticate with current credentials", e);
		}
		bucket = new S3Bucket(source.getHost());
		basedir = getBaseDir(source);
	}

	protected boolean doesRemoteResourceExist(String resourceName) {
		try {
			service.getObjectDetails(bucket, basedir + resourceName);
		}
		catch (S3ServiceException e) {
			return false;
		}
		return true;
	}

	protected void disconnectFromRepository() {
		// Nothing to do for S3
	}

	protected void getResource(String resourceName, File destination, TransferProgress progress)
			throws ResourceDoesNotExistException, S3ServiceException, IOException {
		S3Object object;
		try {
			object = service.getObject(bucket, basedir + resourceName);
		}
		catch (S3ServiceException e) {
			throw new ResourceDoesNotExistException("Resource " + resourceName + " does not exist in the repository", e);
		}

		if(!destination.getParentFile().exists()) {
			destination.getParentFile().mkdirs();
		}

		InputStream in = null;
		OutputStream out = null;
		try {
			in = object.getDataInputStream();
			out = new TransferProgressFileOutputStream(destination, progress);
			byte[] buffer = new byte[1024];
			int length;
			while ((length = in.read(buffer)) != -1) {
				out.write(buffer, 0, length);
			}
		}
		finally {
			if (in != null) {
				try {
					in.close();
				}
				catch (IOException e) {
					// Nothing possible at this point
				}
			}
			if (out != null) {
				try {
					out.close();
				}
				catch (IOException e) {
					// Nothing possible at this point
				}
			}
		}
	}

	protected boolean isRemoteResourceNewer(String resourceName, long timestamp) throws S3ServiceException {
		S3Object object = service.getObjectDetails(bucket, basedir + resourceName);
		return object.getLastModifiedDate().compareTo(new Date(timestamp)) < 0;
	}

	protected List<String> listDirectory(String directory) throws Exception {
		S3Object[] objects = service.listObjects(bucket, basedir + directory, "");
		List<String> fileNames = new ArrayList<String>(objects.length);
		for (S3Object object : objects) {
			fileNames.add(object.getKey());
		}
		return fileNames;
	}

	protected void putResource(File source, String destination, TransferProgress progress) throws S3ServiceException,
			IOException {
		buildDestinationPath(getDestinationPath(destination));
		S3Object object = new S3Object(basedir + destination);
		object.setDataInputFile(source);
		object.setContentLength(source.length());

		InputStream in = null;
		try {
			service.putObject(bucket, object);

			in = new FileInputStream(source);
			byte[] buffer = new byte[1024];
			int length;
			while ((length = in.read(buffer)) != -1) {
				progress.notify(buffer, length);
			}
		}
		finally {
			if (in != null) {
				try {
					in.close();
				}
				catch (IOException e) {
					// Nothing possible at this point
				}
			}
		}
	}

	private void buildDestinationPath(String destination) throws S3ServiceException {
		S3Object object = new S3Object(basedir + destination + "/");
		object.setContentLength(0);
		service.putObject(bucket, object);
		int index = destination.lastIndexOf('/');
		if (index != -1) {
			buildDestinationPath(destination.substring(0, index));
		}
	}

	private String getDestinationPath(String destination) {
		return destination.substring(0, destination.lastIndexOf('/'));
	}

	private String getBaseDir(Repository source) {
		StringBuilder sb = new StringBuilder(source.getBasedir());
		sb.deleteCharAt(0);
		if (sb.charAt(sb.length() - 1) != '/') {
			sb.append('/');
		}
		return sb.toString();
	}

	private AWSCredentials getCredentials(AuthenticationInfo authenticationInfo) throws AuthenticationException {
		if (authenticationInfo == null) {
			return null;
		}
		String accessKey = authenticationInfo.getUserName();
		String secretKey = authenticationInfo.getPassphrase();
		if (accessKey == null || secretKey == null) {
			throw new AuthenticationException("S3 requires a username and passphrase to be set");
		}
		return new AWSCredentials(accessKey, secretKey);
	}
}
