/*
 * Created on 23 juin 2004
$Id$
 */
package org.exist.xquery.test;

import java.io.File;
import java.io.IOException;

import junit.framework.TestCase;

import org.exist.xmldb.DatabaseInstanceManager;
import org.exist.xmldb.LocalCollection;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;
import org.xmldb.api.DatabaseManager;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Database;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.CollectionManagementService;
import org.xmldb.api.modules.XMLResource;
import org.xmldb.api.modules.XPathQueryService;

/** This TestCase is for direct storage of SAX events in the database; one has to implement an XMLReader. 
 * It is also a stress test that creates large documents using SAX, use main() for this.               
 * @author jmv
 */
public class SAXStorageTest extends TestCase {
	/** */
	public SAXStorageTest(String s) {
		super(s);
	}

	private XMLResource doc;
	private Collection root;
	private static String FILE_STORED;
	
	protected void setUp() {
		try {
			// initialize driver
			Class cl = Class.forName("org.exist.xmldb.DatabaseImpl");
			Database database = (Database) cl.newInstance();
			database.setProperty("create-database", "true");
			DatabaseManager.registerDatabase(database);
			root = DatabaseManager.getCollection(
								"xmldb:exist:///db",
								"admin",
								null);
			CollectionManagementService service =
				(CollectionManagementService) root.getService(
					"CollectionManagementService",
					"1.0");
			root = service.createCollection("test");
			FILE_STORED = "big.xml";
			doc = (XMLResource) root.createResource(FILE_STORED, "XMLResource");
		} catch (ClassNotFoundException e) {
		} catch (InstantiationException e) {
		} catch (IllegalAccessException e) {
		} catch (XMLDBException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	/**
	 * @param xquery
	 * @param mess
	 * @return TODO
	 * @throws XMLDBException
	 */
	private ResourceSet querySingleLine(String xquery, String mess) throws XMLDBException {
		// query a single line:
		XPathQueryService service =
			(XPathQueryService) root.getService(
					"XPathQueryService", 	"1.0");
		ResourceSet result = null;
		if ( xquery != "") {
			// xquery = "/*/*[2]";
			System.out.println("Querying \""+xquery+"\" ..." );
			long t0 = System.currentTimeMillis();
			result = service.queryResource( "big.xml", xquery );
			// assertEquals(1, result.getSize());
			long t1 = System.currentTimeMillis();
			System.out.println("Time for query \""+xquery+"\" on "+ mess + ": " + ( t1-t0) + " ms." );
		}
		return result;
	}
	
	/** Store in the "classical" eXist way: the XMLResource stores an XML string before
	 * storeResource() stores it in the database.
	 * @throws XMLDBException
	 * @throws SAXException
	 */
	public void testQueryStoreContentAsSAX() throws XMLDBException, SAXException {
		ContentHandler databaseInserter = doc.setContentAsSAX();
		(new TabularXMLReader()).writeDocument(databaseInserter);
		root.storeResource(doc);
		querySingleLine("", "testQueryStoreContentAsSAX");
	}

	/** Store in the new way: the XMLResource stores just a File object before
	 * storeResource() stores the SAX events in the database.
	 * @throws XMLDBException
*/	
	public void testQueryBigDocument() throws XMLDBException{
		XMLReader dataSource = new TabularXMLReader();
		storeSAXEvents(dataSource);
		ResourceSet result = querySingleLine("", "testQueryBigDocument");
		assertEquals(1, result.getSize());
	}

	/**
	 * @param dataSource
	 * @throws XMLDBException
	 */
	private void storeSAXEvents(XMLReader dataSource) throws XMLDBException {
		if ( root instanceof LocalCollection ) {
			long t0 = System.currentTimeMillis();
			LocalCollection coll = (LocalCollection)root;
			coll.setReader(dataSource);
			doc.setContent(new File(FILE_STORED));
			coll.storeResource(doc);
			long t1 = System.currentTimeMillis();
			System.out.println("Time for storing:  " + ( t1-t0) + " ms." );
		}
	}

	/** arguments: lines , columns, XQuery string */
	public static void main(String[] args) throws XMLDBException {
		String xquery = "";
		int lines = 20; int columns = 20;
		if ( args.length >= 2 ) {
			lines = Integer.parseInt(args[0]);
			columns = Integer.parseInt(args[1]);
		}
		if ( args.length == 3 ) {
			xquery = args[2];
		}
		if ( args.length < 2 ) {
			System.out.println("Taking default values");
		}
		
		SAXStorageTest tester = new SAXStorageTest(null);
		tester.setUp();
		XMLReader dataSource = new TabularXMLReader( lines , columns);
		tester.storeSAXEvents(dataSource);
		System.out.println("Stored tabular data, " +lines+" lines, "+columns+" columns");
		
		if ( xquery != "" ) {
			ResourceSet result = tester.querySingleLine(xquery, "testQueryBigDocument" );
			System.out.println("result size: " + result.getSize() );
		}
		shutdown( tester.root );
	}
	
	private static void shutdown(Collection collection) throws XMLDBException {
		//		shutdown the database gracefully
		DatabaseInstanceManager manager =
			(DatabaseInstanceManager) collection.getService("DatabaseInstanceManager", "1.0");
		manager.shutdown();
	}
}
