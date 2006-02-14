/**
 * Copyright (c) 2005, KoLmafia development team
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
 *  [3] Neither the name "KoLmafia development team" nor the names of
 *      its contributors may be used to endorse or promote products
 *      derived from this software without specific prior written
 *      permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package net.sourceforge.kolmafia;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.BorderLayout;
import javax.swing.BoxLayout;

import javax.swing.JOptionPane;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.BorderFactory;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;

// spellcast-related imports
import net.java.dev.spellcast.utilities.PanelList;
import net.java.dev.spellcast.utilities.PanelListCell;
import net.java.dev.spellcast.utilities.LockableListModel;
import net.java.dev.spellcast.utilities.JComponentUtilities;

public class StoreManageFrame extends KoLPanelFrame
{
	private JLabel searchLabel;
	private LockableListModel priceSummary;

	private AddItemPanel addItem;
	private StoreItemPanelList storeItemList;
	private JPanel searchResults;

	public StoreManageFrame( KoLmafia client )
	{
		super( client, "Store Manager" );

		if ( client != null )
			(new RequestThread( new StoreManageRequest( client ) )).start();

		setResizable( false );

		JTabbedPane tabs = new JTabbedPane();
		tabs.add( "Price Setup", new StoreManagePanel() );
		tabs.add( "Bulk Additions", new StoreAddPanel() );
		tabs.add( "Bulk Removals", new StoreRemovePanel() );

		framePanel.add( tabs, BorderLayout.CENTER );
	}

	private class StoreAddPanel extends ItemManagePanel
	{
		public StoreAddPanel()
		{	 super( "On-Hand Inventory", "add selected", "end of run sale", KoLCharacter.getInventory() );
		}

		public void actionConfirmed()
		{
			if ( JOptionPane.NO_OPTION == JOptionPane.showConfirmDialog( null,
				"Are you sure you would like to place the selected items in your store?",
					"Sell request nag screen!", JOptionPane.YES_NO_OPTION ) )
						return;

			(new RequestThread( new AutoSellRequest( client, elementList.getSelectedValues(), AutoSellRequest.AUTOMALL ) )).start();
		}

		public void actionCancelled()
		{
			client.disableDisplay();
			if ( client != null && client instanceof KoLmafiaGUI )
				((KoLmafiaGUI)client).makeEndOfRunSaleRequest();
			client.enableDisplay();
		}
	}

	private class StoreRemovePanel extends ItemManagePanel
	{
		public StoreRemovePanel()
		{	 super( "Store's Inventory", "remove selected", "empty out store", StoreManager.getSoldItemList() );
		}

		public void actionConfirmed()
		{
			 Object [] items = elementList.getSelectedValues();
			 StoreManageRequest [] requests = new StoreManageRequest[ items.length ];

			 for ( int i = 0; i < items.length; ++i )
			 	requests[i] = new StoreManageRequest( client, ((StoreManager.SoldItem)items[i]).getItemID() );

			(new RequestThread( requests )).start();
		}

		public void actionCancelled()
		{
			client.disableDisplay();
			if ( client != null && client instanceof KoLmafiaGUI )
				((KoLmafiaGUI)client).removeAllItemsFromStore();
			client.enableDisplay();
		}
	}

	public void setEnabled( boolean isEnabled )
	{
		if ( addItem != null )
			addItem.setEnabled( isEnabled );

		if ( storeItemList != null )
			storeItemList.setEnabled( isEnabled );
	}

	private class StoreManagePanel extends KoLPanel
	{
		public StoreManagePanel()
		{
			super( "save changes", "auto-undercut", new Dimension( 0, 0 ), new Dimension( 520, 25 ) );

			priceSummary = new LockableListModel();
			JPanel headerPanel = new JPanel();
			headerPanel.setLayout( new BoxLayout( headerPanel, BoxLayout.Y_AXIS ) );

			JPanel labelPanel1 = new JPanel( new BorderLayout() );
			labelPanel1.add( JComponentUtilities.createLabel( "Add to Your Store", JLabel.CENTER,
				Color.black, Color.white ), BorderLayout.CENTER );

			headerPanel.add( labelPanel1 );
			addItem = new AddItemPanel();
			headerPanel.add( addItem );

			headerPanel.add( Box.createVerticalStrut( 20 ) );

			JPanel labelPanel2 = new JPanel( new BorderLayout() );
			labelPanel2.add( JComponentUtilities.createLabel( "Price Management", JLabel.CENTER,
				Color.black, Color.white ), BorderLayout.CENTER );

			headerPanel.add( labelPanel2 );

			storeItemList = new StoreItemPanelList();
			JScrollPane storeItemScrollArea = new JScrollPane( storeItemList,
				JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER );

			JPanel elementsPanel = new JPanel( new BorderLayout() );
			elementsPanel.add( headerPanel, BorderLayout.NORTH );
			elementsPanel.add( storeItemScrollArea );

			VerifiableElement [] elements = new VerifiableElement[0];
			searchResults = new SearchResultsPanel();

			setContent( elements, null, searchResults, true, true );
			container.add( elementsPanel, BorderLayout.CENTER );
		}

		public void actionConfirmed()
		{
			client.updateDisplay( DISABLE_STATE, "Compiling reprice data..." );

			java.awt.Component [] components = storeItemList.getComponents();
			int [] itemID = new int[ components.length ];
			int [] prices = new int[ components.length ];
			int [] limits = new int[ components.length ];

			StoreItemPanel currentPanel;
			for ( int i = 0; i < components.length; ++i )
			{
				currentPanel = (StoreItemPanel) components[i];
				itemID[i] = currentPanel.getItemID();
				prices[i] = currentPanel.getPrice();
				limits[i] = currentPanel.getLimit();
			}

			client.enableDisplay();
			(new RequestThread( new StoreManageRequest( client, itemID, prices, limits ) )).start();
		}

		public void actionCancelled()
		{
			client.disableDisplay();
			if ( client != null && client instanceof KoLmafiaGUI )
				((KoLmafiaGUI)client).priceItemsAtLowestPrice();
			client.enableDisplay();
		}
	}

	/**
	 * An internal class which represents the panel used for tallying the
	 * results of the mall search request.  Note that all of the tallying
	 * functionality is handled by the <code>LockableListModel</code>
	 * provided, so this functions as a container for that list model.
	 */

	private class SearchResultsPanel extends JPanel
	{
		public SearchResultsPanel()
		{
			super( new BorderLayout() );
			setBorder( BorderFactory.createLineBorder( Color.black, 1 ) );
			searchLabel = JComponentUtilities.createLabel( "Mall Prices", JLabel.CENTER,
				Color.black, Color.white );

			add( searchLabel, BorderLayout.NORTH );
			JComponentUtilities.setComponentSize( searchLabel, 150, 16 );

			JList resultsDisplay = new JList( priceSummary );
			resultsDisplay.setPrototypeCellValue( "1234567890ABCDEF" );
			resultsDisplay.setSelectionMode( ListSelectionModel.MULTIPLE_INTERVAL_SELECTION );
			JScrollPane scrollArea = new JScrollPane( resultsDisplay, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER );

			add( scrollArea, BorderLayout.CENTER );
		}
	}

	private class AddItemPanel extends JPanel
	{
		private JLabel lowestPrice;
		private JComboBox sellingList;
		private JTextField itemPrice, itemLimit, itemQty;
		private JButton addButton, searchButton;

		public AddItemPanel()
		{
			sellingList = new JComboBox( KoLCharacter.getSellables() );
			sellingList.setRenderer( AdventureResult.getAutoSellCellRenderer() );

			itemPrice = new JTextField( "" );
			itemLimit = new JTextField( "" );
			itemQty = new JTextField( "" );
			lowestPrice = new JLabel( "(unknown)", JLabel.CENTER );

			addButton = new JButton( JComponentUtilities.getSharedImage( "icon_success_sml.gif" ) );
			addButton.addActionListener( new AddButtonListener() );
			addButton.setToolTipText( "Add to Store" );

			searchButton = new JButton( JComponentUtilities.getSharedImage( "icon_warning_sml.gif" ) );
			searchButton.addActionListener( new SearchButtonListener() );
			searchButton.setToolTipText( "Price Analysis" );

			JComponentUtilities.setComponentSize( sellingList, 280, 20 );
			JComponentUtilities.setComponentSize( itemPrice, 80, 20 );
			JComponentUtilities.setComponentSize( lowestPrice, 80, 20 );
			JComponentUtilities.setComponentSize( itemLimit, 50, 20 );
			JComponentUtilities.setComponentSize( itemQty, 50, 20 );
			JComponentUtilities.setComponentSize( addButton, 30, 20 );
			JComponentUtilities.setComponentSize( searchButton, 30, 20 );

			JPanel corePanel = new JPanel();
			corePanel.setLayout( new BoxLayout( corePanel, BoxLayout.X_AXIS ) );
			corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( sellingList ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( itemPrice ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( lowestPrice ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( itemLimit ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( itemQty ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( addButton ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( searchButton ); corePanel.add( Box.createHorizontalStrut( 30 ) );

			JLabel [] label = new JLabel[6];
			label[0] = new JLabel( "Item Name", JLabel.CENTER );  JComponentUtilities.setComponentSize( label[0], 280, 20 );
			label[1] = new JLabel( "Price", JLabel.CENTER );  JComponentUtilities.setComponentSize( label[1], 80, 20 );
			label[2] = new JLabel( "Lowest", JLabel.CENTER );  JComponentUtilities.setComponentSize( label[2], 80, 20 );
			label[3] = new JLabel( "Lim", JLabel.CENTER );  JComponentUtilities.setComponentSize( label[3], 50, 20 );
			label[4] = new JLabel( "Qty", JLabel.CENTER );  JComponentUtilities.setComponentSize( label[4], 50, 20 );
			label[5] = new JLabel( "Action", JLabel.CENTER );  JComponentUtilities.setComponentSize( label[5], 70, 20 );

			JPanel labelPanel = new JPanel();
			labelPanel.setLayout( new BoxLayout( labelPanel, BoxLayout.X_AXIS ) );
			labelPanel.add( Box.createHorizontalStrut( 10 ) );
			for ( int i = 0; i < label.length; ++i )
			{
				labelPanel.add( label[i] );
				labelPanel.add( Box.createHorizontalStrut( 10 ) );
			}

			labelPanel.add( Box.createHorizontalStrut( 20 ) );

			setLayout( new BoxLayout( this, BoxLayout.Y_AXIS ) );
			add( Box.createVerticalStrut( 5 ) );
			add( labelPanel );
			add( Box.createVerticalStrut( 5 ) );
			add( corePanel );
		}

		public void setEnabled( boolean isEnabled )
		{
			sellingList.setEnabled( isEnabled );
			itemPrice.setEnabled( isEnabled );
			itemLimit.setEnabled( isEnabled );
			itemQty.setEnabled( isEnabled );
			addButton.setEnabled( isEnabled );
			searchButton.setEnabled( isEnabled );
		}

		private class AddButtonListener implements ActionListener
		{
			public void actionPerformed( ActionEvent e )
			{
				if ( !KoLCharacter.hasStore() )
				{
					JOptionPane.showMessageDialog( null, "Sorry, you don't have a store." );
					return;
				}

				AdventureResult soldItem = (AdventureResult) sellingList.getSelectedItem();
				if ( soldItem == null )
					return;

				int price = getValue( itemPrice, StoreManager.getPrice( soldItem.getItemID() ) );
				int limit = getValue( itemLimit );
				int qty = getValue( itemQty, soldItem.getCount() );

				soldItem = new AdventureResult( soldItem.getItemID(), qty );

				if ( price > 10 )
					(new RequestThread( new AutoSellRequest( client, soldItem, price, limit ) )).start();
			}
		}

		private class SearchButtonListener implements ActionListener
		{
			public void actionPerformed( ActionEvent e )
			{
				if ( sellingList.getSelectedItem() == null )
					return;

				client.disableDisplay();
				StoreManager.searchMall( ((AdventureResult)sellingList.getSelectedItem()).getName(), priceSummary );
				searchLabel.setText( ((AdventureResult)sellingList.getSelectedItem()).getName() );
				client.enableDisplay();
			}
		}
	}

	private class StoreItemPanelList extends PanelList
	{
		public StoreItemPanelList()
		{	super( 16, 680, 30, StoreManager.getSoldItemList() );
		}

		protected synchronized PanelListCell constructPanelListCell( Object value, int index )
		{
			StoreItemPanel toConstruct = new StoreItemPanel( (StoreManager.SoldItem) value );
			toConstruct.updateDisplay( this, value, index );
			return toConstruct;
		}
	}

	private class StoreItemPanel extends JPanel implements PanelListCell
	{
		private int itemID;
		private JLabel itemName, itemQuantity, lowestPrice;
		private JTextField itemPrice, itemLimit;
		private JButton takeButton, searchButton;

		public StoreItemPanel( StoreManager.SoldItem value )
		{
			itemID = value.getItemID();
			itemName = new JLabel( TradeableItemDatabase.getItemName( itemID ), JLabel.RIGHT );
			itemQuantity = new JLabel( df.format( value.getQuantity() ), JLabel.CENTER );
			itemPrice = new JTextField( df.format( value.getPrice() ) );
			lowestPrice = new JLabel( value.getLowest() == 0 ? "(unknown)" : df.format( value.getLowest() ), JLabel.CENTER );
			itemLimit = new JTextField( df.format( value.getLimit() ) );

			takeButton = new JButton( JComponentUtilities.getSharedImage( "icon_error_sml.gif" ) );
			takeButton.addActionListener( new TakeButtonListener() );
			takeButton.setToolTipText( "Remove Items" );

			searchButton = new JButton( JComponentUtilities.getSharedImage( "icon_warning_sml.gif" ) );
			searchButton.addActionListener( new SearchButtonListener() );
			searchButton.setToolTipText( "Price Analysis" );

			JComponentUtilities.setComponentSize( itemName, 280, 20 );
			JComponentUtilities.setComponentSize( itemPrice, 80, 20 );
			JComponentUtilities.setComponentSize( lowestPrice, 80, 20 );
			JComponentUtilities.setComponentSize( itemLimit, 50, 20 );
			JComponentUtilities.setComponentSize( itemQuantity, 50, 20 );
			JComponentUtilities.setComponentSize( takeButton, 30, 20 );
			JComponentUtilities.setComponentSize( searchButton, 30, 20 );

			JPanel corePanel = new JPanel();
			corePanel.setLayout( new BoxLayout( corePanel, BoxLayout.X_AXIS ) );
			corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( itemName ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( itemPrice ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( lowestPrice ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( itemLimit ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( itemQuantity ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( takeButton ); corePanel.add( Box.createHorizontalStrut( 10 ) );
			corePanel.add( searchButton ); corePanel.add( Box.createHorizontalStrut( 10 ) );

			setLayout( new BoxLayout( this, BoxLayout.Y_AXIS ) );
			add( Box.createVerticalStrut( 5 ) );
			add( corePanel );
		}

		public synchronized void updateDisplay( PanelList list, Object value, int index )
		{
			StoreManager.SoldItem smsi = (StoreManager.SoldItem) value;

			itemName.setText( TradeableItemDatabase.getItemName( smsi.getItemID() ) );
			itemQuantity.setText( df.format( smsi.getQuantity() ) );
			itemPrice.setText( df.format( smsi.getPrice() ) );
			lowestPrice.setText( smsi.getLowest() == 0 ? "**" : df.format( smsi.getLowest() ) );
			itemLimit.setText( df.format( smsi.getLimit() ) );
		}

		public void setEnabled( boolean isEnabled )
		{
			itemPrice.setEnabled( isEnabled );
			itemLimit.setEnabled( isEnabled );
			takeButton.setEnabled( isEnabled );
			searchButton.setEnabled( isEnabled );
		}

		public int getItemID()
		{	return itemID;
		}

		public int getPrice()
		{
			try
			{
				return df.parse( itemPrice.getText() ).intValue();
			}
			catch ( Exception e )
			{
				e.printStackTrace( KoLmafia.getLogStream() );
				e.printStackTrace();

				return 0;
			}
		}

		public int getLimit()
		{
			try
			{
				return df.parse( itemLimit.getText() ).intValue();
			}
			catch ( Exception e )
			{
				e.printStackTrace( KoLmafia.getLogStream() );
				e.printStackTrace();

				return 0;
			}
		}

		private class TakeButtonListener implements ActionListener
		{
			public void actionPerformed( ActionEvent e )
			{
				client.disableDisplay();
				StoreManager.takeItem( itemID );
				client.enableDisplay();
			}
		}

		private class SearchButtonListener implements ActionListener
		{
			public void actionPerformed( ActionEvent e )
			{
				client.disableDisplay();
				StoreManager.searchMall( TradeableItemDatabase.getItemName( itemID ), priceSummary );
				searchLabel.setText( itemName.getText() );
				client.enableDisplay();
			}
		}
	}

	public static void main( String [] args )
	{	(new CreateFrameRunnable( StoreManageFrame.class )).run();
	}
}
