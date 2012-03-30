/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.deployers;

import java.util.LinkedList;
import java.util.concurrent.Executor;

import org.teiid.adminapi.AdminProcessingException;
import org.teiid.adminapi.Model;
import org.teiid.adminapi.VDB;
import org.teiid.adminapi.impl.ModelMetaData;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.adminapi.impl.VDBTranslatorMetaData;
import org.teiid.core.TeiidException;
import org.teiid.dqp.internal.datamgr.ConnectorManager;
import org.teiid.dqp.internal.datamgr.ConnectorManagerRepository;
import org.teiid.dqp.internal.datamgr.TranslatorRepository;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.runtime.RuntimePlugin;
import org.teiid.translator.ExecutionFactory;


public abstract class VDBStatusChecker {
	private static final String JAVA_CONTEXT = "java:/"; //$NON-NLS-1$
	private VDBRepository vdbRepository;
	private TranslatorRepository translatorRepository;
	
	public VDBStatusChecker(VDBRepository vdbRepository) {
		this.vdbRepository = vdbRepository;
	}
	
	public void translatorAdded(String translatorName) {
		resourceAdded(translatorName, true);
	}
	
	public void translatorRemoved(String translatorName) {
		resourceRemoved(translatorName, true);
	}
	
	public void dataSourceAdded(String dataSourceName) {
		if (dataSourceName.startsWith(JAVA_CONTEXT)) {
			dataSourceName = dataSourceName.substring(5);
		}
		resourceAdded(dataSourceName, false);
	}
	
	public void dataSourceRemoved(String dataSourceName) {
		if (dataSourceName.startsWith(JAVA_CONTEXT)) {
			dataSourceName = dataSourceName.substring(5);
		}
		resourceRemoved(dataSourceName, false);
	}	

	public void dataSourceReplaced(String vdbName, int vdbVersion,
			String modelName, String sourceName, String translatorName,
			String dsName) throws AdminProcessingException {
		if (dsName.startsWith(JAVA_CONTEXT)) {
			dsName = dsName.substring(5);
		}		
		
		VDBMetaData vdb = this.vdbRepository.getVDB(vdbName, vdbVersion);
		ModelMetaData model = vdb.getModel(modelName);

		synchronized (vdb) {
			ConnectorManagerRepository cmr = vdb.getAttachment(ConnectorManagerRepository.class);
			ConnectorManager cm = cmr.getConnectorManager(sourceName);
			ExecutionFactory<Object, Object> ef = cm.getExecutionFactory();
			
			boolean dsReplaced = false;
			if (!cm.getConnectionName().equals(dsName)){
				vdb.setStatus(VDB.Status.INACTIVE);
				String msg = RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40076, vdb.getName(), vdb.getVersion(), model.getSourceTranslatorName(sourceName), dsName);
				model.addError(ModelMetaData.ValidationError.Severity.ERROR.name(), msg);
				cm = new ConnectorManager(translatorName, dsName); 
				cm.setExecutionFactory(ef);
				cmr.addConnectorManager(sourceName, cm);
				dsReplaced = true;
			}
			
			if (!cm.getTranslatorName().equals(translatorName)) {
				try {
					TranslatorRepository repo = vdb.getAttachment(TranslatorRepository.class);
					VDBTranslatorMetaData t = null;
					if (repo != null) {
						t = repo.getTranslatorMetaData(translatorName);
					}
					if (t == null) {
						t = this.translatorRepository.getTranslatorMetaData(translatorName);
					}
					if (t == null) {
						 throw new AdminProcessingException(RuntimePlugin.Event.TEIID40032, RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40032, vdb.getName(), vdb.getVersion(), translatorName));
					}
					ef = TranslatorUtil.buildExecutionFactory(t, t.getAttachment(ClassLoader.class));
					cm.setExecutionFactory(ef);
				} catch (TeiidException e) {
					 throw new AdminProcessingException(RuntimePlugin.Event.TEIID40033, e.getCause());
				}
			}
			
			if (dsReplaced) {
				resourceAdded(dsName, false);
			}
		}
	}
	
	public void setVDBRepository(VDBRepository repo) {
		this.vdbRepository = repo;
	}	
	
	public void resourceAdded(String resourceName, boolean translator) {
		for (VDBMetaData vdb:this.vdbRepository.getVDBs()) {
			if (vdb.getStatus() == VDB.Status.ACTIVE || vdb.isPreview()) {
				continue;
			}
			LinkedList<Runnable> runnables = new LinkedList<Runnable>();
			synchronized (vdb) {
				ConnectorManagerRepository cmr = vdb.getAttachment(ConnectorManagerRepository.class);
				
				for (ModelMetaData model:vdb.getModelMetaDatas().values()) {
					if (model.getErrors().isEmpty()) {
						continue;
					}
	
					String sourceName = getSourceName(resourceName, model, translator);
					if (sourceName == null) {
						continue;
					}

					ConnectorManager cm = cmr.getConnectorManager(sourceName);
					String status = cm.getStausMessage();
					if (status != null && status.length() > 0) {
						model.addError(ModelMetaData.ValidationError.Severity.ERROR.name(), status);
						LogManager.logInfo(LogConstants.CTX_RUNTIME, status);					
					} else {
						//get the pending metadata load
						Runnable r = model.removeAttachment(Runnable.class);
						if (r != null) {
							runnables.add(r);
						} else {
							model.clearErrors();
						}
					}
				}
	
				boolean valid = true;
				for (ModelMetaData model:vdb.getModelMetaDatas().values()) {
					if (!model.getErrors().isEmpty()) {
						valid = false;
						break;
					}
				}
				
				if (!runnables.isEmpty()) {
					//the task themselves will set the status on completion/failure
					for (Runnable runnable : runnables) {						
						getExecutor().execute(runnable);
					}
				} else if (valid) {
					vdb.setStatus(VDB.Status.ACTIVE);
					LogManager.logInfo(LogConstants.CTX_RUNTIME, RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40003,vdb.getName(), vdb.getVersion()));
				}
			}
		}
	}
	
	public void resourceRemoved(String resourceName, boolean translator) {
		for (VDBMetaData vdb:this.vdbRepository.getVDBs()) {
			if (vdb.isPreview()) {
				continue;
			}
			synchronized (vdb) {
				for (Model m:vdb.getModels()) {
					ModelMetaData model = (ModelMetaData)m;
					
					String sourceName = getSourceName(resourceName, model, translator);
					if (sourceName != null) {
						vdb.setStatus(VDB.Status.INACTIVE);
						String msg = null;
						if (translator) {
							msg = RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40005, vdb.getName(), vdb.getVersion(), model.getSourceTranslatorName(sourceName));
						}
						else {
							msg = RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40012, vdb.getName(), vdb.getVersion(), resourceName); 
						}
						model.addError(ModelMetaData.ValidationError.Severity.ERROR.name(), msg);
						LogManager.logInfo(LogConstants.CTX_RUNTIME, msg);					
						LogManager.logInfo(LogConstants.CTX_RUNTIME, RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40006,vdb.getName(), vdb.getVersion()));
					}
				}
			}
		}
	}

	private String getSourceName(String translatorName, ModelMetaData model, boolean translator) {
		for (String sourceName:model.getSourceNames()) {
			if (translator) {
				if (translatorName.equals(model.getSourceTranslatorName(sourceName))) {
					return sourceName;
				}
			} else {
				String jndiName = model.getSourceConnectionJndiName(sourceName);
				if (jndiName.startsWith(JAVA_CONTEXT)) {
					jndiName = jndiName.substring(5);
				}
				if (translatorName.equals(jndiName)) {
					return sourceName;
				}
			}
		}
		return null;
	}
	
	public abstract Executor getExecutor();
	
	public void setTranslatorRepository(TranslatorRepository repo) {
		this.translatorRepository = repo;
	}
}
