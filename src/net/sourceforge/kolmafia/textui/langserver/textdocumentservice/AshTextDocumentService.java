package net.sourceforge.kolmafia.textui.langserver.textdocumentservice;

import net.sourceforge.kolmafia.textui.langserver.AshLanguageServer;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.services.TextDocumentService;

public abstract class AshTextDocumentService implements TextDocumentService {
  protected final AshLanguageServer parent;

  public AshTextDocumentService(final AshLanguageServer parent) {
    this.parent = parent;
  }

  public final void setCapabilities(final ServerCapabilities capabilities) {
    TextDocumentSyncOptions textDocumentSyncOptions = new TextDocumentSyncOptions();
    textDocumentSyncOptions.setOpenClose(false);
    textDocumentSyncOptions.setChange(TextDocumentSyncKind.None);
    textDocumentSyncOptions.setWillSave(false);
    textDocumentSyncOptions.setWillSaveWaitUntil(false);
    textDocumentSyncOptions.setSave(new SaveOptions(false));

    capabilities.setTextDocumentSync(textDocumentSyncOptions);
  }

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {}

  @Override
  public void didChange(DidChangeTextDocumentParams params) {}

  @Override
  public void didClose(DidCloseTextDocumentParams params) {}

  @Override
  public void didSave(DidSaveTextDocumentParams params) {}
}