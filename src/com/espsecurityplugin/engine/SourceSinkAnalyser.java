package com.espsecurityplugin.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Vector;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.osgi.framework.Bundle;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.espsecurityplugin.activator.Activator;
import com.espsecurityplugin.feedback.EclipseMarkerFeedback;
import com.espsecurityplugin.interfaces.FeedbackInstance;
import com.espsecurityplugin.rules.JavaKeyLoader;

public class SourceSinkAnalyser extends ASTVisitor {
	
	// find based on QualifiedName. We've resolved bindings.
	
		Collection<String> sourceKeys;
		Collection<String> sinkKeys;
		Collection<String> validationKeys;
		List<String> taintedVariables;
		
		Collection<FeedbackInstance> feedbackList;
		
		public SourceSinkAnalyser() throws IOException, ParserConfigurationException, SAXException {
			// get rules file from properties
			String sourceRuleLocation = Activator.getDefault().getPreferenceStore().getString("sourcerules.location");
			InputStream sourceInputStream;
			if(sourceRuleLocation == null || sourceRuleLocation.isEmpty()) {
				Bundle bundle = Platform.getBundle("ESPSecurityPlugin");
				URL url = bundle.getResource("resources/sourceRules.xml");
				URLConnection urlConnection = url.openConnection();
				sourceInputStream = urlConnection.getInputStream();
			} else {
				File file = new File(sourceRuleLocation);
				sourceInputStream = new FileInputStream(file);
			}
			
			SAXParserFactory saxFactory = SAXParserFactory.newInstance();
			SAXParser saxParser = saxFactory.newSAXParser();
			DefaultHandler sourceHandler = new JavaKeyLoader();
			saxParser.parse(sourceInputStream, sourceHandler);
			sourceInputStream.close();
			
			sourceKeys = new ArrayList<String>();
			sourceKeys.addAll(((JavaKeyLoader) sourceHandler).getList());
			
			
			String sinkRuleLocation = Activator.getDefault().getPreferenceStore().getString("sinkrules.location");
			InputStream sinkInputStream;
			if(sinkRuleLocation == null || sinkRuleLocation.isEmpty()) {
				Bundle bundle = Platform.getBundle("ESPSecurityPlugin");
				URL url = bundle.getResource("resources/sinkRules.xml");
				URLConnection urlConnection = url.openConnection();
				sinkInputStream = urlConnection.getInputStream();
			} else {
				File file = new File(sinkRuleLocation);
				sinkInputStream = new FileInputStream(file);
			}
			
			saxFactory = SAXParserFactory.newInstance();
			saxParser = saxFactory.newSAXParser();
			DefaultHandler sinkHandler = new JavaKeyLoader();
			saxParser.parse(sinkInputStream, sinkHandler);
			sinkInputStream.close();
			
			sinkKeys = new ArrayList<String>();
			sinkKeys.addAll(((JavaKeyLoader) sinkHandler).getList());
			
			
			String validationRuleLocation = Activator.getDefault().getPreferenceStore().getString("sinkrules.location");
			InputStream validationInputStream;
			if(validationRuleLocation == null || validationRuleLocation.isEmpty()) {
				Bundle bundle = Platform.getBundle("ESPSecurityPlugin");
				URL url = bundle.getResource("resources/validationRules.xml");
				URLConnection urlConnection = url.openConnection();
				validationInputStream = urlConnection.getInputStream();
			} else {
				File file = new File(sinkRuleLocation);
				validationInputStream = new FileInputStream(file);
			}
			
			saxFactory = SAXParserFactory.newInstance();
			saxParser = saxFactory.newSAXParser();
			DefaultHandler validationHandler = new JavaKeyLoader();
			saxParser.parse(validationInputStream, validationHandler);
			validationInputStream.close();
			
			validationKeys = new ArrayList<String>();
			validationKeys.addAll(((JavaKeyLoader) validationHandler).getList());
			
			feedbackList = new ArrayList<FeedbackInstance>();
			taintedVariables = new ArrayList<String>();
		}
		
		/*
		 * identifier = expression;
		 * 
		 * check if expression is a source expression. if it is, add identifier to a list.
		 */
		@Override
		public boolean visit(VariableDeclarationFragment node) {
			if(expressionContainsSource(node.getInitializer())) {
				taintedVariables.addAll(simpleNameToString(node.getName()));
			}
			return true;
		}
		
		@Override
		public boolean visit(Assignment node) {
			if(expressionContainsSource(node.getRightHandSide())) {
				taintedVariables.addAll(simpleNameToString((SimpleName) node.getLeftHandSide()));
			}
			if(expressionContainsTaintedVariable(node.getRightHandSide())) {
				taintedVariables.addAll(expressionToString(node.getLeftHandSide()));
			}
			if(expressionContainsValidation(node.getRightHandSide())) {
				taintedVariables.removeAll(expressionToString(node.getLeftHandSide()));
			}
			return true;
		}
		
		@Override
		public boolean visit(MethodInvocation node) {
			IMethodBinding methodBinding = node.resolveMethodBinding();
			if(methodBinding == null) return true;
			String nodeKey = methodBinding.getKey();
			
			for(String sinkKey : sinkKeys) {
				if(sinkKey.equals(nodeKey)) {
					// is an argument a taintedVar?
					for(Object expression : node.arguments()) {
						for(String taintedVar : taintedVariables) {
							if(matches((Expression)expression, taintedVar)) {
								createFeedback(node);
							}
						}
					}
				}
			}
			
			for(String validationKey : validationKeys) {
				if(validationKey.equals(nodeKey)) {
					// if any argument is a taintedvar:
					for(Object expression : node.arguments()) {
						for(String taintedVar : taintedVariables) {
							if(matches((Expression)expression, taintedVar)) {
								// It has been passed to a validation method
								taintedVariables.remove(taintedVar);
							}
						}
					}
				}
			}
			return true;
		}
		
		private boolean expressionContainsSource(Expression expression) {
			if(expression instanceof MethodInvocation) {
				return expressionContainsSource((MethodInvocation) expression);
			}
			return false;
		}
		
		private boolean expressionContainsSource(MethodInvocation node) {
			IMethodBinding methodBinding = node.resolveMethodBinding();
			String nodeKey = methodBinding.getKey();
			for(String sourceKey : sourceKeys) {
				if(sourceKey.equals(nodeKey)) {
					return true;
				}
			}
			return false;
		}
		
		private boolean expressionContainsValidation(Expression expression) {
			if(expression instanceof MethodInvocation) {
				return expressionContainsValidation((MethodInvocation) expression);
			}
			return false;
		}
		
		private boolean expressionContainsValidation(MethodInvocation node) {
			IMethodBinding methodBinding = node.resolveMethodBinding();
			String nodeKey = methodBinding.getKey();
			for(String validationKey : validationKeys) {
				if(validationKey.equals(nodeKey)) {
					return true;
				}
			}
			return false;
		}
		
		private Collection<String> simpleNameToString(SimpleName simpleName) {
			Collection<String> result = new ArrayList<String>();
			result.add(simpleName.getIdentifier());
			return result;
		}
		
		private boolean expressionContainsTaintedVariable(Expression node) {
			if(node instanceof SimpleName) {
				return expressionContainsTaintedVariable((SimpleName) node);
			} else if (node instanceof ArrayAccess) {
				return expressionContainsTaintedVariable((ArrayAccess)node);
			} else if (node instanceof ArrayCreation) {
				return expressionContainsTaintedVariable((ArrayCreation)node);
			} else if (node instanceof CastExpression) {
				return expressionContainsTaintedVariable((CastExpression) node);
			} else if (node instanceof ClassInstanceCreation) {
				return expressionContainsTaintedVariable((ClassInstanceCreation)node);
			} else if (node instanceof ConditionalExpression) {
				return expressionContainsTaintedVariable((ConditionalExpression)node);
			} else if(node instanceof FieldAccess) {
				return expressionContainsTaintedVariable((FieldAccess)node);
			} else if(node instanceof InfixExpression) {
				return expressionContainsTaintedVariable((InfixExpression) node);
			} else if(node instanceof MethodInvocation) {
				return expressionContainsTaintedVariable((MethodInvocation) node);
			}  else if(node instanceof ParenthesizedExpression) {
				return expressionContainsTaintedVariable((ParenthesizedExpression) node);
			} else if(node instanceof PostfixExpression) {
				return expressionContainsTaintedVariable((PostfixExpression) node);
			} else if(node instanceof PrefixExpression) {
				return expressionContainsTaintedVariable((PrefixExpression) node);
			} else if(node instanceof SuperMethodInvocation) {
				return expressionContainsTaintedVariable((SuperMethodInvocation) node);
			}
			
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(ArrayAccess node) {
			if(expressionContainsTaintedVariable(node.getArray())) {
				return true;
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(ArrayCreation node) {
			if(expressionContainsTaintedVariable(node.getInitializer())) {
				return true;
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(ArrayInitializer node) {
			for(Object expression : node.expressions()) {
				if(expressionContainsTaintedVariable((Expression)expression)) {
					return true;
				}
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(CastExpression node) {
			return expressionContainsTaintedVariable(node.getExpression());
		}

		
		private boolean expressionContainsTaintedVariable(ClassInstanceCreation node) {
			for(Object expression : node.arguments()) {
				if(expressionContainsTaintedVariable((Expression) expression)) {
					return true;
				}
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(ConditionalExpression node) {
			if(expressionContainsTaintedVariable(node.getThenExpression()) 
					|| expressionContainsTaintedVariable(node.getElseExpression())) {
				return true;
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(FieldAccess node) {
			if(expressionContainsTaintedVariable(node.getExpression())) {
				return true;
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(InfixExpression node) {
			if(expressionContainsTaintedVariable(node.getLeftOperand()) ||
					expressionContainsTaintedVariable(node.getRightOperand())) {
				return true;
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(MethodInvocation node) {
			for(Object expression : node.arguments()) {
				if(expressionContainsTaintedVariable((Expression) expression)) {
					return true;
				}
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(ParenthesizedExpression node) {
			return expressionContainsTaintedVariable(node.getExpression());
		}
		
		private boolean expressionContainsTaintedVariable(PostfixExpression node) {
			return expressionContainsTaintedVariable(node.getOperand());
		}
		
		private boolean expressionContainsTaintedVariable(PrefixExpression node) {
			return expressionContainsTaintedVariable(node.getOperand());
		}
		
		private boolean expressionContainsTaintedVariable(SuperMethodInvocation node) {
			for(Object expression : node.arguments()) {
				if(expressionContainsTaintedVariable((Expression)expression)) {
					return true;
				}
			}
			return false;
		}
		
		private boolean expressionContainsTaintedVariable(SimpleName node) {
			String potentiallyTaintedVariable = node.getIdentifier();
			for(String taintedVariable : taintedVariables) {
				if(taintedVariable.equals(potentiallyTaintedVariable)) {
					return true;
				}
			}
			return false;
		}
		
		protected Collection<String> expressionToString(Expression expression) {
			
			Collection<String> result = new Vector<String>(1); // Will 1 be most common case?

			if(expression == null) {
				return result;
			}

			if(expression instanceof ArrayAccess) {
				result.addAll(expressionToString(((ArrayAccess) expression).getArray()));
			}  else if(expression instanceof Assignment) {
				result.addAll(expressionToString(((Assignment) expression).getLeftHandSide()));
			} else if(expression instanceof CastExpression) {
				result.addAll(expressionToString(((CastExpression) expression).getExpression()));
			}else if(expression instanceof ClassInstanceCreation) {
				// TODO determine if .getExpression() needs to be analysed.can't tell looking at docs
				result.addAll(expressionToSimpleName(CastUtils.castList(Expression.class, ((ClassInstanceCreation) expression).arguments())));
			}  else if(expression instanceof ConditionalExpression) {
				// TODO what can I put in an if statement? assignment?
				result.addAll(expressionToString(((ConditionalExpression) expression).getThenExpression()));
				result.addAll(expressionToString(((ConditionalExpression) expression).getElseExpression()));
			} else if(expression instanceof MethodInvocation) {
				result.addAll(expressionToSimpleName(CastUtils.castList(Expression.class, ((MethodInvocation) expression).arguments())));
				result.addAll(expressionToString(((MethodInvocation) expression).getExpression()));
			}  else if(expression instanceof QualifiedName) {
				result.add(((QualifiedName) expression).getName().getIdentifier());
			}  else if(expression instanceof SimpleName) {
				result.add(((SimpleName)expression).getIdentifier());
			}   else if(expression instanceof ParenthesizedExpression) {
				result.addAll(expressionToString(((ParenthesizedExpression) expression).getExpression()));
			}  else if(expression instanceof PostfixExpression) {
				result.addAll(expressionToString(((PostfixExpression) expression).getOperand()));
			}  else if(expression instanceof PrefixExpression) {
				result.addAll(expressionToString(((PrefixExpression) expression).getOperand()));
			}  else if (expression instanceof SuperMethodInvocation) {
				result.addAll(expressionToSimpleName(CastUtils.castList(Expression.class, ((SuperMethodInvocation) expression).arguments())));
			} 
			
			return result;
		}

		private Collection<? extends String> expressionToSimpleName(List<Expression> arguments) {
			Vector<String> result = new Vector<String>(1);
			
			for(Object expression : arguments) {
				result.addAll(expressionToString((Expression) expression));
			}
			
			return result;
		}
		
		
		public Collection<FeedbackInstance> getFeedback() {
			return feedbackList;
		}
		
		private boolean matches(Expression expression, String taintedVariable) {
			if(expression instanceof SimpleName) {
				return matches((SimpleName)expression, taintedVariable);
			}
			return false;
		}
		
		private boolean matches(SimpleName expression, String taintedVariable) {
			return taintedVariable.equals(expression.getIdentifier());
		}

		private void createFeedback(ASTNode node) {
			FeedbackInstance feedback = new EclipseMarkerFeedback();
			feedback.setMessage("Validate data before using it!"); // TODO configurable message
			feedback.setStartPosition(node.getStartPosition());
			feedback.setOffset(node.getLength());
			feedbackList.add(feedback);
		}

		public void cleanup() {
			feedbackList.clear();
			taintedVariables.clear();
		}
}
