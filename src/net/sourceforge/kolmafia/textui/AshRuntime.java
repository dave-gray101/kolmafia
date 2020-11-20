/**
 * Copyright (c) 2005-2020, KoLmafia development team
 * http://kolmafia.sourceforge.net/
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  [1] Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *  [2] Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in
 *      the documentation and/or other materials provided with the
 *      distribution.
 *  [3] Neither the name "KoLmafia" nor the names of its contributors may
 *      be used to endorse or promote products derived from this software
 *      without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION ) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE ) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package net.sourceforge.kolmafia.textui;

import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import net.sourceforge.kolmafia.KoLConstants.MafiaState;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.RequestThread;
import net.sourceforge.kolmafia.StaticEntity;

import net.sourceforge.kolmafia.preferences.Preferences;

import net.sourceforge.kolmafia.request.RelayRequest;
import net.sourceforge.kolmafia.request.SendMailRequest;

import net.sourceforge.kolmafia.textui.parsetree.Function;
import net.sourceforge.kolmafia.textui.parsetree.FunctionList;
import net.sourceforge.kolmafia.textui.parsetree.Scope;
import net.sourceforge.kolmafia.textui.parsetree.Type;
import net.sourceforge.kolmafia.textui.parsetree.Value;
import net.sourceforge.kolmafia.textui.parsetree.VariableList;
import net.sourceforge.kolmafia.textui.parsetree.VariableReference;

import net.sourceforge.kolmafia.utilities.CharacterEntities;
import net.sourceforge.kolmafia.utilities.NullStream;

public class AshRuntime implements ScriptRuntime
{
	protected Parser parser;
	protected Scope scope;

	// Variables used during execution

	private static final Stack<AshRuntime> interpreterStack = new Stack<>();

	private ScriptRuntime.State currentState = ScriptRuntime.State.NORMAL;
	private boolean exiting = false;
	private int traceIndentation = 0;
	public Profiler profiler;

	// key, then aggregate, then iterator for every active foreach loop
	public ArrayList<Object> iterators = new ArrayList<>();

	// For use in runtime error messages
	private String fileName;
	private int lineNumber;

	// For use in LibraryFunction return values
	private boolean hadPendingState;

	// For use by RuntimeLibrary's CLI command batching feature
	LinkedHashMap<String, LinkedHashMap<String, StringBuilder>> batched;

	// For ASH stack traces.
	private final ArrayList<CallFrame> frameStack;
	// Limit object churn across function calls.
	private final ArrayList<CallFrame> unusedCallFrames;

	public static final int STACK_LIMIT = 10;

	// For use in ASH relay scripts
	private RelayRequest relayRequest = null;
	private StringBuffer serverReplyBuffer = null;

	// GLOBAL control of tracing
	private static PrintStream traceStream = NullStream.INSTANCE;

	public static boolean isTracing()
	{
		return AshRuntime.traceStream != NullStream.INSTANCE;
	}

	public static void openTraceStream()
	{
		AshRuntime.traceStream =
			RequestLogger.openStream( "ASH_" + KoLConstants.DAILY_FORMAT.format( new Date() ) + ".txt", AshRuntime.traceStream, true );
	}

	public static void println(final String string )
	{
		AshRuntime.traceStream.println( string );
	}

	public static void closeTraceStream()
	{
		RequestLogger.closeStream( AshRuntime.traceStream );
		AshRuntime.traceStream = NullStream.INSTANCE;
	}

	public AshRuntime()
	{
		this.parser = new Parser();
		this.scope = new Scope( new VariableList(), Parser.getExistingFunctionScope() );
		this.hadPendingState = false;
		this.frameStack = new ArrayList<>();
		this.unusedCallFrames = new ArrayList<>();
	}

	public void initializeRelayScript( final RelayRequest request )
	{
		this.relayRequest = request;
		if ( this.serverReplyBuffer == null )
		{
			this.serverReplyBuffer = new StringBuffer();
		}
		else
		{
			this.serverReplyBuffer.setLength( 0 );
		}

		// Allow a relay script to execute regardless of error state
		KoLmafia.forceContinue();
	}

	@Override
	public RelayRequest getRelayRequest()
	{
		return this.relayRequest;
	}

	@Override
	public StringBuffer getServerReplyBuffer()
	{
		return this.serverReplyBuffer;
	}

	public void finishRelayScript()
	{
		this.relayRequest = null;
		this.serverReplyBuffer = null;
	}

	public void cloneRelayScript( final ScriptRuntime caller )
	{
		this.finishRelayScript();
		if ( caller != null )
		{
			this.relayRequest = caller.getRelayRequest();
			this.serverReplyBuffer = caller.getServerReplyBuffer();
		}
	}

	public Parser getParser()
	{
		return this.parser;
	}

	public String getFileName()
	{
		return this.parser.getFileName();
	}

	public Map<File, Long> getImports()
	{
		return this.parser.getImports();
	}

	public FunctionList getFunctions()
	{
		return this.scope.getFunctions();
	}

	@Override
	public ScriptRuntime.State getState()
	{
		return this.currentState;
	}

	public void setState( final ScriptRuntime.State state )
	{
		this.currentState = state;

		if (state == ScriptRuntime.State.EXIT && Preferences.getBoolean( "printStackOnAbort" ) )
		{
			this.printStackTrace();
		}
	}

	public static void rememberPendingState()
	{
		if ( AshRuntime.interpreterStack.isEmpty() )
		{
			return;
		}

		AshRuntime current = AshRuntime.interpreterStack.peek();

		current.hadPendingState = true;
	}

	public static void forgetPendingState()
	{
		if ( AshRuntime.interpreterStack.isEmpty() )
		{
			return;
		}

		AshRuntime current = AshRuntime.interpreterStack.peek();

		current.hadPendingState = false;
	}

	public static boolean getContinueValue()
	{
		if ( !KoLmafia.permitsContinue() )
		{
			return false;
		}

		if ( AshRuntime.interpreterStack.isEmpty() )
		{
			return true;
		}

		AshRuntime current = AshRuntime.interpreterStack.peek();

		return !current.hadPendingState;
	}

	public void setLineAndFile( final String fileName, final int lineNumber )
	{
		this.fileName = fileName;
		this.lineNumber = lineNumber;
	}

	private static final String indentation = " " + " " + " ";
	public static void indentLine(final PrintStream stream, final int indent )
	{
		if ( stream != null && stream != NullStream.INSTANCE )
		{
			for ( int i = 0; i < indent; ++i )
			{
				stream.print( indentation );
			}
		}
	}

	// **************** Parsing and execution *****************

	public boolean validate( final File scriptFile, final InputStream stream )
	{
		try
		{
			this.parser = new Parser( scriptFile, stream, null );
			this.scope = parser.parse();
			this.resetTracing();
			if ( AshRuntime.isTracing() )
			{
				this.printScope( this.scope );
			}
			return true;
		}
		catch ( ScriptException e )
		{
			String message = CharacterEntities.escape( e.getMessage() );
			KoLmafia.updateDisplay( MafiaState.ERROR, message );
			return false;
		}
		catch ( Exception e )
		{
			StaticEntity.printStackTrace( e );
			return false;
		}
	}

	public Value execute( final String functionName, final Object[] parameters )
	{
		String currentScript = this.getFileName() == null ? "<>" : "<" + this.getFileName() + ">";
		String notifyList = Preferences.getString( "previousNotifyList" );
		String notifyRecipient = this.parser.getNotifyRecipient();

		if ( notifyRecipient != null && !notifyList.contains( currentScript ) )
		{
			Preferences.setString( "previousNotifyList", notifyList + currentScript );

			SendMailRequest notifier = new SendMailRequest( notifyRecipient, this );
			RequestThread.postRequest( notifier );
		}

		return this.execute( functionName, parameters, true );
	}

	public Value execute( final String functionName, final Object[] parameters, final boolean executeTopLevel )
	{
		try
		{
			return this.executeScope( this.scope, functionName, parameters, executeTopLevel );
		}
		catch ( ScriptException e )
		{
			KoLmafia.updateDisplay( MafiaState.ERROR, e.getMessage() );
		}
		catch ( StackOverflowError e )
		{
			KoLmafia.updateDisplay( MafiaState.ERROR, "Stack overflow during ASH script: " + Parser.getLineAndFile( this.fileName, this.lineNumber ) );
		}
		catch ( Exception e )
		{
			String lineAndFile = Parser.getLineAndFile( this.fileName, this.lineNumber );
			StaticEntity.printStackTrace( e, lineAndFile, true );
			KoLmafia.updateDisplay( MafiaState.ERROR, "Script execution aborted (" + e.getMessage() + "): " + lineAndFile );
		}
		return DataTypes.VOID_VALUE;
	}

	private Value executeScope( final Scope topScope, final String functionName, final Object[] parameters,
				    final boolean executeTopLevel )
	{
		Function main;
		Value result = null;

		AshRuntime.interpreterStack.push( this );

		this.currentState = ScriptRuntime.State.NORMAL;
		this.exiting = false;
		this.resetTracing();

		if ( functionName.equals( "main" ) )
		{
			main = this.parser.getMainMethod();
		}
		else
		{
			main = topScope.findFunction( functionName, parameters != null );

			if ( main == null && topScope.getCommandList().isEmpty() )
			{
				KoLmafia.updateDisplay( MafiaState.ERROR, "Unable to invoke " + functionName );
				return DataTypes.VOID_VALUE;
			}
		}

		// First execute top-level commands;

		if ( executeTopLevel )
		{
			if ( AshRuntime.isTracing() )
			{
				this.trace( "Executing top-level commands" );
			}
			result = topScope.execute( this );
		}

		if (this.currentState == ScriptRuntime.State.EXIT)
		{
			return result;
		}

		// Now execute main function, if any
		if ( main != null )
		{
			if ( AshRuntime.isTracing() )
			{
				this.trace( "Executing main function" );
			}
			// push to interpreter stack
			this.pushFrame( "main" );

			Object[] values = new Object[ main.getVariableReferences().size() + 1];
			values[ 0 ] = this;
			
			if ( !this.requestUserParams( main, parameters, values ) )
			{
				return null;
			}

			result = main.execute( this, values );
			this.popFrame();
		}
		AshRuntime.interpreterStack.pop();

		return result;
	}

	private boolean requestUserParams( final Function targetFunction, final Object[] parameters, Object[] values )
	{
		int args = parameters == null ? 0 : parameters.length;
		Type type = null;
		int index = 0;

		for ( VariableReference param : targetFunction.getVariableReferences() )
		{
			type = param.getType();

			String name = param.getName();
			Value value = null;

			while ( value == null )
			{
				if ( type == DataTypes.VOID_TYPE )
				{
					value = DataTypes.VOID_VALUE;
					break;
				}

				Object input = ( index >= args ) ?
					DataTypes.promptForValue( type, name ) :
					parameters[ index ];

				// User declined to supply a parameter
				if ( input == null )
				{
					return false;
				}

				try
				{
					value = DataTypes.coerceValue( type, input, false );
				}
				catch ( Exception e )
				{
					value = null;
				}

				if ( value == null )
				{
					RequestLogger.printLine( "Bad " + type.toString() + " value: \"" + input + "\"" );

					// Punt if parameter came from the CLI
					if ( index < args )
					{
						return false;
					}
				}
			}

			values[ ++index ] = value;
		}

		if ( index < args && type != null )
		{
			StringBuilder inputs = new StringBuilder();
			for ( int i = index - 1; i < args; ++i )
			{
				inputs.append( parameters[ i ] );
				inputs.append( " " );
			}

			Value value = DataTypes.parseValue( type, inputs.toString().trim(), true );
			values[ index ] = value;
		}

		return true;
	}

	// **************** Debug printing *****************

	private void printScope( final Scope scope )
	{
		if ( scope == null )
		{
			return;
		}

		PrintStream stream = traceStream;
		scope.print( stream, 0 );

		Function mainMethod = this.parser.getMainMethod();
		if ( mainMethod != null )
		{
			this.indentLine( 1 );
			stream.println( "<MAIN>" );
			mainMethod.print( stream, 2 );
		}
	}

	// ************** Call  Stack ***************

	public static class CallFrame
	{
		private String name;
		private int lineNumber;
		private String fileName;

		public CallFrame( String name, int lineNumber, String fileName )
		{
			this.reset( name, lineNumber, fileName );
		}

		public CallFrame reset( String name, int lineNumber, String fileName )
		{
			this.name = name;
			this.lineNumber = lineNumber;
			this.fileName = fileName;

			return this;
		}

		public String getName()
		{
			return name;
		}

		public String getFileName()
		{
			return fileName;
		}

		public int getLineNumber()
		{
			return lineNumber;
		}

		public String toString()
		{
			return " at " + name + ", " + fileName + ":" + lineNumber;
		}
	}

	private CallFrame getCallFrame( String name, int lineNumber, String fileName )
	{
		if ( unusedCallFrames.size() == 0 )
		{
			return new CallFrame( name, lineNumber, fileName );
		}
		return unusedCallFrames.remove( unusedCallFrames.size() - 1 ).reset( name, lineNumber, fileName );
	}

	public void pushFrame( String name )
	{
		frameStack.add( getCallFrame( name, this.lineNumber, this.fileName ) );
	}

	public CallFrame popFrame()
	{
		// Unclear when/why we sometimes have an empty stack.
		if ( frameStack.size() == 0 )
		{
			return null;
		}
		CallFrame frame = frameStack.remove( frameStack.size() - 1 );
		unusedCallFrames.add( frame );
		return frame;
	}

	@SuppressWarnings("unchecked") 
	public List<CallFrame> getCallFrames()
	{
		return (List<CallFrame>) frameStack.clone();
	}

	private String getStackTrace()
	{
		StringBuilder s = new StringBuilder();
		String fileName = null;
		int lineNumber = 0;
		int stacks = 0;
		while ( frameStack.size() != 0 && stacks < STACK_LIMIT )
		{
			stacks++;
			CallFrame current = popFrame();
			if ( fileName == null )
			{
				fileName = current.getFileName();
				lineNumber = current.getLineNumber();
				continue;
			}
			s.append( KoLConstants.LINE_BREAK);
			s.append( "\u00A0\u00A0at " );
			s.append( current.getName() );
			s.append( " (" );
			s.append( fileName );
			s.append( ":" );
			s.append( lineNumber );
			s.append( ")" );
			fileName = current.getFileName();
			lineNumber = current.getLineNumber();
		}

		frameStack.clear();
		return s.toString();
	}

	public void printStackTrace()
	{
		// We may attempt to print the stack trace multiple times if in RuntimeController.State.EXIT.
		if ( this.frameStack.size() > 0 )
		{
			String stackTrace = this.getStackTrace();
			RequestLogger.printLine( "Stack trace:" );
			RequestLogger.printLine( stackTrace );
			RequestLogger.updateSessionLog( "Stack trace:" );
			RequestLogger.updateSessionLog( stackTrace );

		}
	}

	// **************** Tracing *****************

	public final void resetTracing()
	{
		this.traceIndentation = 0;
	}

	private void indentLine(final int indent )
	{
		if ( AshRuntime.isTracing() )
		{
			AshRuntime.indentLine( traceStream, indent );
		}
	}

	public final void traceIndent()
	{
		this.traceIndentation++ ;
	}

	public final void traceUnindent()
	{
		this.traceIndentation-- ;
	}

	public final void trace( final String string )
	{
		if ( AshRuntime.isTracing() )
		{
			this.indentLine( this.traceIndentation );
			traceStream.println( string );
		}
	}

	public final void setExiting()
	{
		this.exiting = true;
	}

	public final void captureValue( final Value value )
	{
		// We've just executed a command in a context that captures the
		// return value.

		// If the script specifically exits, don't override that
		if ( this.exiting )
		{
			return;
		}

		if ( KoLmafia.refusesContinue() || value == null )
		{
			// User aborted
			this.setState( ScriptRuntime.State.EXIT );
			return;
		}

		// Even if an error occurred, since we captured the result,
		// permit further execution.

		this.setState( ScriptRuntime.State.NORMAL );
		KoLmafia.forceContinue();
	}

	@Override
	public final ScriptException runtimeException( final String message )
	{
		return this.runtimeException( message, this.fileName, this.lineNumber );
	}

	public final ScriptException runtimeException( final String message, final String fileName, final int lineNumber )
	{
		return new ScriptException( message + " " + Parser.getLineAndFile( fileName, lineNumber ) + this.getStackTrace() );
	}

	@Override
	public final ScriptException runtimeException2( final String message1, final String message2 )
	{
		return runtimeException2( message1, message2, this.fileName, this.lineNumber );
	}

	public static ScriptException runtimeException2(final String message1, final String message2, final String fileName, final int lineNumber )
	{
		return new ScriptException( message1 + " " + Parser.getLineAndFile( fileName, lineNumber ) + " " + message2);
	}

	public final ScriptException undefinedFunctionException( final String name, final List<Value> params )
	{
		return this.runtimeException( Parser.undefinedFunctionMessage( name, params ) );
	}

	@Override
	public LinkedHashMap<String, LinkedHashMap<String, StringBuilder>> getBatched() {
		return batched;
	}

	@Override
	public void setBatched(LinkedHashMap<String, LinkedHashMap<String, StringBuilder>> batched) {
		this.batched = batched;
	}
}