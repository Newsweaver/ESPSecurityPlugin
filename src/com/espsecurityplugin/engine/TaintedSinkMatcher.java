package com.espsecurityplugin.engine;

import java.io.IOException;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.xml.sax.SAXException;

import com.espsecurityplugin.activator.Activator;
import com.espsecurityplugin.feedback.ConcreteContextModel;
import com.espsecurityplugin.feedback.EclipseMarkerFeedbackReporter;
import com.espsecurityplugin.interfaces.FeedbackInstance;
import com.espsecurityplugin.interfaces.FeedbackReporter;

public class TaintedSinkMatcher implements Runnable {
	
	private final Logger LOG = Activator.getLogger();
	
	private ASTVisitor sourceAnalyser;
	private FeedbackReporter feedbackReporter;
	
	public TaintedSinkMatcher() {
		try {
			sourceAnalyser = new SourceSinkAnalyser();
		} catch (IOException e) {
			LOG.log(Level.WARNING, "ioe");
		} catch (ParserConfigurationException e) {
			LOG.log(Level.WARNING, "pce");
		} catch (SAXException e) {
			LOG.log(Level.WARNING, "se");
		}
		
		feedbackReporter = new EclipseMarkerFeedbackReporter();
	}
	
	Logger LOGGER = Activator.getLogger();

	@Override
	public void run() {
		LOG.log(Level.INFO, "Entering run");
		// Clear the markers. Otherwise, when we disable the plugin, they'll hang around forever.
		try {
			LOG.log(Level.INFO, "Clearing markers");
			ConcreteContextModel.getContextModel().getResource().deleteMarkers("ESPSecurityPlugin.secproblem", true, IResource.DEPTH_ZERO);
		} catch (CoreException e) {
			LOGGER.log(Level.WARNING, e.getMessage());
		}
		LOG.log(Level.INFO, "Checking to see if ESP enabled...");
		// next ensure we're enabled
		Boolean disabled = Activator.getDefault().getPreferenceStore().getBoolean("esp.disabled");
		if(disabled) {
			// Don't create the AST, dn't analyse.
			LOG.log(Level.INFO, "ESP is currently disabled!!!");
			return;
		} 
		LOG.log(Level.INFO, "ESP is currently enabled, generating AST");
		
		ASTParser astParser = ASTParser.newParser(AST.JLS4);
		astParser.setKind(ASTParser.K_COMPILATION_UNIT);
		astParser.setSource(ConcreteContextModel.getContextModel().getCompilationUnit());
		astParser.setResolveBindings(true);
		CompilationUnit target = (CompilationUnit) astParser.createAST(null); // TODO IProgressMonitor
		LOG.log(Level.INFO, "AST generated. analysing...");
		
		// now run the SourceAnalyser, TaintAnalyser and SinkAnalyser in order
		target.accept(sourceAnalyser);
		LOG.log(Level.INFO, "Analysis finished, getting feedback.");
		Collection<FeedbackInstance> feedbackList = ((SourceSinkAnalyser) sourceAnalyser).getFeedback();
		LOG.log(Level.INFO, "Looping through feedback list, size: " + feedbackList.size());
		for(FeedbackInstance feedback : feedbackList) {
			LOG.log(Level.INFO, "Feedback: " + feedback.toString());
			try {
				feedbackReporter.sendFeedback(feedback);
			} catch (Exception e) {
				LOG.log(Level.WARNING, "Could not send feedback: " + e.getMessage());
			}
		}
		// Now, clear the three lists.
		((SourceSinkAnalyser) sourceAnalyser).cleanup();
		
	}

}
