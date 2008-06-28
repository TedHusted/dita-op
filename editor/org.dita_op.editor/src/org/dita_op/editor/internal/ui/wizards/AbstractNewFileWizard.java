/**
 * Copyright (C) 2008 Claude Vedovini <http://vedovini.net/>.
 * 
 * This file is part of the DITA Open Platform <http://www.dita-op.org/>.
 * 
 * The DITA Open Platform is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * 
 * The DITA Open Platform is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * The DITA Open Platform. If not, see <http://www.gnu.org/licenses/>.
 */
package org.dita_op.editor.internal.ui.wizards;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;

import org.dita_op.editor.internal.Activator;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.templates.ContextTypeRegistry;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.jface.text.templates.TemplateBuffer;
import org.eclipse.jface.text.templates.TemplateContext;
import org.eclipse.jface.text.templates.TemplateContextType;
import org.eclipse.jface.text.templates.TemplateException;
import org.eclipse.jface.text.templates.TemplateTranslator;
import org.eclipse.jface.text.templates.persistence.TemplatePersistenceData;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWizard;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.wst.xml.ui.internal.XMLUIPlugin;
import org.eclipse.wst.xml.ui.internal.templates.TemplateContextTypeIdsXML;

@SuppressWarnings("restriction")//$NON-NLS-1$
public abstract class AbstractNewFileWizard extends Wizard implements
		INewWizard {
	private GenericWizardPage page;
	private ISelection selection;
	private final String templateId;
	private String defaultFileName;
	private String[] fileExtensions;
	private String title;
	private String description;

	public AbstractNewFileWizard(String templateId) {
		super();
		setNeedsProgressMonitor(true);
		this.templateId = templateId;
	}

	public void setDefaultFileName(String defaultFileName) {
		this.defaultFileName = defaultFileName;
	}

	public void setFileExtensions(String[] fileExtensions) {
		this.fileExtensions = fileExtensions;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public void addPages() {
		page = new GenericWizardPage(selection);
		page.setTitle(title);
		page.setDescription(description);
		page.setDefaultFileName(defaultFileName);

		if (fileExtensions != null) {
			page.setFileExtensions(fileExtensions);
		}

		addPage(page);
	}

	/**
	 * This method is called when 'Finish' button is pressed in the wizard. We
	 * will create an operation and run it using wizard as execution context.
	 */
	public boolean performFinish() {
		final String containerName = page.getContainerName();
		final String fileName = page.getFileName();

		IRunnableWithProgress op = new IRunnableWithProgress() {
			public void run(IProgressMonitor monitor)
					throws InvocationTargetException {
				try {
					doFinish(containerName, fileName, monitor);
				} catch (CoreException e) {
					throw new InvocationTargetException(e);
				} finally {
					monitor.done();
				}
			}
		};

		try {
			getContainer().run(true, false, op);
		} catch (InterruptedException e) {
			return false;
		} catch (InvocationTargetException e) {
			Throwable realException = e.getTargetException();
			MessageDialog.openError(
					getShell(),
					Messages.getString("AbstractNewFileWizard.ErrorDialog.title"), //$NON-NLS-1$
					realException.getMessage());
			return false;
		}

		return true;
	}

	/**
	 * The worker method. It will find the container, create the file if missing
	 * or just replace its contents, and open the editor on the newly created
	 * file.
	 */
	private void doFinish(String containerName, String fileName,
			IProgressMonitor monitor) throws CoreException {
		// create a sample file
		monitor.beginTask(
				MessageFormat.format(
						Messages.getString("AbstractNewFileWizard.Job.title"), fileName), 2); //$NON-NLS-1$
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IResource resource = root.findMember(new Path(containerName));
		if (!resource.exists() || !(resource instanceof IContainer)) {
			throw newCoreException(MessageFormat.format(
					Messages.getString("AbstractNewFileWizard.containerDoesNotExist"), containerName)); //$NON-NLS-1$
		}
		IContainer container = (IContainer) resource;
		final IFile file = container.getFile(new Path(fileName));
		try {
			InputStream stream = openContentStream();
			if (file.exists()) {
				file.setContents(stream, true, true, monitor);
			} else {
				file.create(stream, true, monitor);
			}
			stream.close();
		} catch (IOException e) {
		}
		monitor.worked(1);
		monitor.setTaskName(Messages.getString("AbstractNewFileWizard.openingFile.message")); //$NON-NLS-1$
		getShell().getDisplay().asyncExec(new Runnable() {
			public void run() {
				IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
				try {
					IDE.openEditor(page, file, true);
				} catch (PartInitException e) {
				}
			}
		});
		monitor.worked(1);
	}

	/**
	 * We will initialize file contents with a sample text.
	 * 
	 * @throws CoreException
	 */
	private InputStream openContentStream() throws CoreException {
		String contents = ""; //$NON-NLS-1$

		try {
			ContextTypeRegistry registry = XMLUIPlugin.getDefault().getTemplateContextRegistry();
			TemplateContextType contextType = registry.getContextType(TemplateContextTypeIdsXML.ALL);
			TemplatePersistenceData data = XMLUIPlugin.getDefault().getTemplateStore().getTemplateData(
					templateId);

			MyTemplateContext ctx = new MyTemplateContext(contextType);
			TemplateBuffer buffer = ctx.evaluate(data.getTemplate().getPattern());
			contents = buffer.getString();
		} catch (BadLocationException e) {
			throw newCoreException(e);
		} catch (TemplateException e) {
			throw newCoreException(e);
		}

		return new ByteArrayInputStream(contents.getBytes());
	}

	private CoreException newCoreException(Exception e) throws CoreException {
		IStatus status = Activator.getDefault().newStatus(IStatus.ERROR, e);
		return new CoreException(status);
	}

	private CoreException newCoreException(String message) throws CoreException {
		IStatus status = new Status(IStatus.ERROR, Activator.PLUGIN_ID,
				IStatus.OK, message, null);
		return new CoreException(status);
	}

	/**
	 * We will accept the selection in the workbench to see if we can initialize
	 * from it.
	 * 
	 * @see IWorkbenchWizard#init(IWorkbench, IStructuredSelection)
	 */
	public void init(IWorkbench workbench, IStructuredSelection selection) {
		this.selection = selection;
	}

	private class MyTemplateContext extends TemplateContext {

		MyTemplateContext(TemplateContextType contextType) {
			super(contextType);
		}

		@Override
		public boolean canEvaluate(Template template) {
			return true;
		}

		public TemplateBuffer evaluate(String template)
				throws BadLocationException, TemplateException {
			TemplateTranslator translator = new TemplateTranslator();
			TemplateBuffer buffer = translator.translate(template);

			getContextType().resolve(buffer, this);
			return buffer;
		}

		@Override
		public TemplateBuffer evaluate(Template template)
				throws BadLocationException, TemplateException {
			TemplateTranslator translator = new TemplateTranslator();
			TemplateBuffer buffer = translator.translate(template);

			getContextType().resolve(buffer, this);
			return buffer;
		}
	}
}