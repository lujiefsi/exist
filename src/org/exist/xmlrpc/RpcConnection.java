/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-03 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist.sourceforge.net
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.xmlrpc;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Vector;
import java.util.WeakHashMap;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;

import org.apache.log4j.Logger;
import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.collections.IndexInfo;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.ArraySet;
import org.exist.dom.BinaryDocument;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.dom.QName;
import org.exist.dom.SortedNodeSet;
import org.exist.memtree.NodeImpl;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.User;
import org.exist.source.Source;
import org.exist.source.StringSource;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.XQueryPool;
import org.exist.storage.serializers.EXistOutputKeys;
import org.exist.storage.serializers.Serializer;
import org.exist.storage.sync.Sync;
import org.exist.util.Configuration;
import org.exist.util.Lock;
import org.exist.util.LockException;
import org.exist.util.Occurrences;
import org.exist.util.SyntaxException;
import org.exist.util.serializer.SAXSerializer;
import org.exist.util.serializer.SAXSerializerPool;
import org.exist.xquery.CompiledXQuery;
import org.exist.xquery.PathExpr;
import org.exist.xquery.Pragma;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.parser.XQueryAST;
import org.exist.xquery.parser.XQueryLexer;
import org.exist.xquery.parser.XQueryParser;
import org.exist.xquery.parser.XQueryTreeParser;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;
import org.exist.xupdate.Modification;
import org.exist.xupdate.XUpdateProcessor;
import org.w3c.dom.DocumentType;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import antlr.collections.AST;

/**
 * This class implements the actual methods defined by
 * {@link org.exist.xmlrpc.RpcAPI}.
 * 
 * @author Wolfgang Meier (wolfgang@exist-db.org)
 */
public class RpcConnection extends Thread {

	private final static Logger LOG = Logger.getLogger(RpcConnection.class);

	public final static String EXIST_NS = "http://exist.sourceforge.net/NS/exist";

	protected BrokerPool brokerPool;
	protected WeakHashMap documentCache = new WeakHashMap();
	protected boolean terminate = false;
	protected List cachedExpressions = new LinkedList();

	protected RpcServer.ConnectionPool connectionPool;

	public RpcConnection(Configuration conf, RpcServer.ConnectionPool pool)
			throws EXistException {
		super();
		connectionPool = pool;
		brokerPool = BrokerPool.getInstance();
	}

	public void createCollection(User user, String name) throws Exception,
			PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Collection current = broker.getOrCreateCollection(name);
			LOG.debug("creating collection " + name);
			broker.saveCollection(current);
			broker.flush();
			//broker.sync();
			LOG.debug("collection " + name + " has been created");
		} catch (Exception e) {
			LOG.debug(e);
			throw e;
		} finally {
			brokerPool.release(broker);
		}
	}

	public String createId(User user, String collName) throws EXistException {
		DBBroker broker = brokerPool.get(user);
		Collection collection = null;
		try {
			collection = broker.openCollection(collName, Lock.READ_LOCK);
			if (collection == null)
				throw new EXistException("collection " + collName
						+ " not found!");
			String id;
			Random rand = new Random();
			boolean ok;
			do {
				ok = true;
				id = Integer.toHexString(rand.nextInt()) + ".xml";
				// check if this id does already exist
				if (collection.hasDocument(id))
					ok = false;

				if (collection.hasSubcollection(id))
					ok = false;

			} while (!ok);
			return id;
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	protected PathExpr compile(User user, DBBroker broker, String xquery,
			Hashtable parameters) throws Exception {
		String baseURI = (String) parameters.get(RpcAPI.BASE_URI);
		XQueryContext context = new XQueryContext(broker);
		context.setBaseURI(baseURI);
		Hashtable namespaces = (Hashtable)parameters.get(RpcAPI.NAMESPACES);
		if(namespaces != null && namespaces.size() > 0) {
			context.declareNamespaces(namespaces);
		}
		//	declare static variables
		Hashtable variableDecls = (Hashtable)parameters.get(RpcAPI.VARIABLES);
		if(variableDecls != null) {
			for (Iterator i = variableDecls.entrySet().iterator(); i.hasNext();) {
				Map.Entry entry = (Map.Entry) i.next();
				LOG.debug("declaring " + entry.getKey().toString() + " = " + entry.getValue());
				context.declareVariable((String) entry.getKey(), entry.getValue());
			}
		}
		LOG.debug("compiling " + xquery);
		XQueryLexer lexer = new XQueryLexer(context, new StringReader(xquery));
		XQueryParser parser = new XQueryParser(lexer);
		XQueryTreeParser treeParser = new XQueryTreeParser(context);

		parser.xpath();
		if (parser.foundErrors()) {
			throw new EXistException(parser.getErrorMessage());
		}

		AST ast = parser.getAST();

		PathExpr expr = new PathExpr(context);
		treeParser.xpath(ast, expr);
		if (treeParser.foundErrors()) {
			throw new EXistException(treeParser.getErrorMessage());
		}
		return expr;
	}

	protected QueryResult doQuery(User user, DBBroker broker, String xpath,
			NodeSet contextSet, Hashtable parameters)
			throws Exception {
		String baseURI = (String) parameters.get(RpcAPI.BASE_URI);
		Source source = new StringSource(xpath);
		XQuery xquery = broker.getXQueryService();
		XQueryPool pool = xquery.getXQueryPool();
		CompiledXQuery compiled = pool.borrowCompiledXQuery(source);
		XQueryContext context;
		if(compiled == null)
		    context = xquery.newContext();
		else
		    context = compiled.getContext();
		context.setBaseURI(baseURI);
		Hashtable namespaces = (Hashtable)parameters.get(RpcAPI.NAMESPACES);
		if(namespaces != null && namespaces.size() > 0) {
			context.declareNamespaces(namespaces);
		}
		//	declare static variables
		Hashtable variableDecls = (Hashtable)parameters.get(RpcAPI.VARIABLES);
		if(variableDecls != null) {
			for (Iterator i = variableDecls.entrySet().iterator(); i.hasNext();) {
				Map.Entry entry = (Map.Entry) i.next();
				LOG.debug("declaring " + entry.getKey().toString() + " = " + entry.getValue());
				context.declareVariable((String) entry.getKey(), entry.getValue());
			}
		}
		Vector staticDocuments = (Vector)parameters.get(RpcAPI.STATIC_DOCUMENTS);
		if(staticDocuments != null) {
			String[] d = new String[staticDocuments.size()];
			int j = 0;
			for (Iterator i = staticDocuments.iterator(); i.hasNext(); j++) {
				String next = (String)i.next();
				d[j] = next;
			}
			context.setStaticallyKnownDocuments(d);
		} else if(baseURI != null) {
			context.setStaticallyKnownDocuments(new String[] { baseURI });
		}
		try {
			if(compiled == null)
			    compiled = xquery.compile(context, source);
			checkPragmas(context, parameters);

		    long start = System.currentTimeMillis();
		    Sequence result = xquery.execute(compiled, contextSet);
		    LOG.info("query took " + (System.currentTimeMillis() - start) + "ms.");
		    return new QueryResult(context, result);
		} catch (XPathException e) {
			return new QueryResult(e);
		} finally {
			if(compiled != null)
				pool.returnCompiledXQuery(source, compiled);
		}
	}

	/**
	 * Check if the XQuery contains pragmas that define serialization settings.
	 * If yes, copy the corresponding settings to the current set of output properties.
	 * 
	 * @param context
	 */
	protected void checkPragmas(XQueryContext context, Hashtable parameters) throws XPathException {
		Pragma pragma = context.getPragma(Pragma.SERIALIZE_QNAME);
		if(pragma == null)
			return;
		String[] contents = pragma.tokenizeContents();
		for(int i = 0; i < contents.length; i++) {
			String[] pair = Pragma.parseKeyValuePair(contents[i]);
			if(pair == null)
				throw new XPathException("Unknown parameter found in " + pragma.getQName().toString() +
						": '" + contents[i] + "'");
			LOG.debug("Setting serialization property from pragma: " + pair[0] + " = " + pair[1]);
			parameters.put(pair[0], pair[1]);
		}
	}
	
	public int executeQuery(User user, String xpath, Hashtable parameters) throws Exception {
		long startTime = System.currentTimeMillis();
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			QueryResult result = doQuery(user, broker, xpath, null,
					parameters);
			if(result.hasErrors())
				throw result.getException();
			result.queryTime = System.currentTimeMillis() - startTime;
			connectionPool.resultSets.put(result.hashCode(), result);
			return result.hashCode();
		} finally {
			brokerPool.release(broker);
		}
	}

	protected String formatErrorMsg(String message) {
		return formatErrorMsg("error", message);
	}

	protected String formatErrorMsg(String type, String message) {
		StringBuffer buf = new StringBuffer();
		buf
				.append("<exist:result xmlns:exist=\"http://exist.sourceforge.net/NS/exist\" ");
		buf.append("hitCount=\"0\">");
		buf.append('<');
		buf.append(type);
		buf.append('>');
		buf.append(message);
		buf.append("</");
		buf.append(type);
		buf.append("></exist:result>");
		return buf.toString();
	}

	public Hashtable getCollectionDesc(User user, String rootCollection)
			throws Exception {
		DBBroker broker = brokerPool.get(user);
		Collection collection = null;
		try {
			if (rootCollection == null)
				rootCollection = "/db";

			collection = broker.openCollection(rootCollection, Lock.READ_LOCK);
			if (collection == null)
				throw new EXistException("collection " + rootCollection
						+ " not found!");
			Hashtable desc = new Hashtable();
			Vector docs = new Vector();
			Vector collections = new Vector();
			if (collection.getPermissions().validate(user, Permission.READ)) {
				DocumentImpl doc;
				Hashtable hash;
				Permission perms;
				for (Iterator i = collection.iterator(broker); i.hasNext(); ) {
					doc = (DocumentImpl) i.next();
					perms = doc.getPermissions();
					hash = new Hashtable(4);
					hash.put("name", doc.getFileName());
					hash.put("owner", perms.getOwner());
					hash.put("group", perms.getOwnerGroup());
					hash
							.put("permissions", new Integer(perms
									.getPermissions()));
					hash.put("type",
							doc.getResourceType() == DocumentImpl.BINARY_FILE
									? "BinaryResource"
									: "XMLResource");
					docs.addElement(hash);
				}
				for (Iterator i = collection.collectionIterator(); i.hasNext(); )
					collections.addElement((String) i.next());
			}
			Permission perms = collection.getPermissions();
			desc.put("collections", collections);
			desc.put("documents", docs);
			desc.put("name", collection.getName());
			desc.put("created", Long.toString(collection.getCreationTime()));
			desc.put("owner", perms.getOwner());
			desc.put("group", perms.getOwnerGroup());
			desc.put("permissions", new Integer(perms.getPermissions()));
			return desc;
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public Hashtable describeResource(User user, String resourceName)
		throws EXistException, PermissionDeniedException {
	    DBBroker broker = brokerPool.get(user);
	    DocumentImpl doc = null;
	    Hashtable hash = new Hashtable(5);
		try {
		    doc = (DocumentImpl) broker.openDocument(resourceName, Lock.READ_LOCK);
			if (doc == null) {
				LOG.debug("document " + resourceName + " not found!");
				return hash;
			}
			if (!doc.getCollection().getPermissions().validate(user, Permission.READ)) {
				throw new PermissionDeniedException("Not allowed to read collection");
			}
			Permission perms = doc.getPermissions();
			hash.put("name", resourceName);
			hash.put("owner", perms.getOwner());
			hash.put("group", perms.getOwnerGroup());
			hash
					.put("permissions", new Integer(perms
							.getPermissions()));
			hash.put("type",
					doc.getResourceType() == DocumentImpl.BINARY_FILE
							? "BinaryResource"
							: "XMLResource");
			hash.put("content-length", new Integer(doc.getContentLength()));
			return hash;
		} finally {
			if(doc != null)
				doc.getUpdateLock().release(Lock.READ_LOCK);
			brokerPool.release(broker);
		}
	}
	
	public Hashtable describeCollection(User user, String rootCollection)
	throws Exception {
		DBBroker broker = brokerPool.get(user);
		Collection collection = null;
		try {
			if (rootCollection == null)
				rootCollection = "/db";
		
			collection = broker.openCollection(rootCollection, Lock.WRITE_LOCK);
			if (collection == null)
				throw new EXistException("collection " + rootCollection
						+ " not found!");
			Hashtable desc = new Hashtable();
			Vector collections = new Vector();
			if (collection.getPermissions().validate(user, Permission.READ)) {
				for (Iterator i = collection.collectionIterator(); i.hasNext(); )
					collections.addElement((String) i.next());
			}
			Permission perms = collection.getPermissions();
			desc.put("collections", collections);
			desc.put("name", collection.getName());
			desc.put("created", Long.toString(collection.getCreationTime()));
			desc.put("owner", perms.getOwner());
			desc.put("group", perms.getOwnerGroup());
			desc.put("permissions", new Integer(perms.getPermissions()));
			return desc;
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}
	
	public String getDocument(User user, String name, Hashtable parametri)
			throws Exception {
		long start = System.currentTimeMillis();
		DBBroker broker = null;

		String stylesheet = null;
		String encoding = "UTF-8";
		String processXSL = "yes";
		Hashtable styleparam = null;

		Collection collection = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			Configuration config = broker.getConfiguration();
			String option = (String) config
					.getProperty("serialization.enable-xinclude");
			String prettyPrint = (String) config
					.getProperty("serialization.indent");

			int pos = name.lastIndexOf('/');
			String collName = name.substring(0, pos);
			String docName = name.substring(pos + 1);
			
			collection = broker.openCollection(collName, Lock.READ_LOCK);
			if (collection == null) {
				LOG.debug("collection " + collName + " not found!");
				return null;
			}
			if(!collection.getPermissions().validate(user, Permission.READ)) {
			    collection.release();
				throw new PermissionDeniedException("Insufficient privileges to read resource");
			}
			doc = collection.getDocumentWithLock(broker, docName);
			collection.release();
			if (doc == null) {
				LOG.debug("document " + name + " not found!");
				throw new EXistException("document not found");
			}
			
			if(!doc.getPermissions().validate(user, Permission.READ))
			    throw new PermissionDeniedException("Insufficient privileges to read resource " + docName);
			Serializer serializer = broker.getSerializer();

			if (parametri != null) {

				for (Enumeration en = parametri.keys(); en.hasMoreElements(); ) {

					String param = (String) en.nextElement();
					String paramvalue = parametri.get(param).toString();
					//LOG.debug("-------Parametri passati:"+param+":
					// "+paramvalue);

					if (param.equals(EXistOutputKeys.EXPAND_XINCLUDES)) {
						option = (paramvalue.equals("yes")) ? "true" : "false";
					}

					if (param.equals(OutputKeys.INDENT)) {
						prettyPrint = paramvalue;
					}

					if (param.equals(OutputKeys.ENCODING)) {
						encoding = paramvalue;
					}

					if (param.equals(EXistOutputKeys.STYLESHEET)) {
						stylesheet = paramvalue;
					}

					if (param.equals(EXistOutputKeys.STYLESHEET_PARAM)) {
						styleparam = (Hashtable) parametri.get(param);
					}

					if (param.equals(OutputKeys.DOCTYPE_SYSTEM)) {
						serializer.setProperty(OutputKeys.DOCTYPE_SYSTEM,
								paramvalue);
					}

					if(param.equals(EXistOutputKeys.PROCESS_XSL_PI)) {
						serializer.setProperty(EXistOutputKeys.PROCESS_XSL_PI, paramvalue);
					}
				}

			}

			if (option.equals("true")) {
				serializer.setProperty(EXistOutputKeys.EXPAND_XINCLUDES, "yes");
			} else {
				serializer.setProperty(EXistOutputKeys.EXPAND_XINCLUDES, "no");
			}

			serializer.setProperty(OutputKeys.ENCODING, encoding);
			serializer.setProperty(OutputKeys.INDENT, prettyPrint);
			if (stylesheet != null) {
				if (stylesheet.indexOf(":") < 0) {
					if (!stylesheet.startsWith("/")) {
						// make path relative to current collection
						stylesheet = collection.getName() + '/' + stylesheet;
					}

				}
				serializer.setStylesheet(stylesheet);

				// set stylesheet param if present
				if (styleparam != null) {
					for (Enumeration en1 = styleparam.keys(); en1
							.hasMoreElements(); ) {
						String param1 = (String) en1.nextElement();
						String paramvalue1 = styleparam.get(param1).toString();
						// System.out.println("-->"+param1+"--"+paramvalue1);
						serializer
								.setStylesheetParamameter(param1, paramvalue1);
					}
				}
			}
			String xml = serializer.serialize(doc);

			return xml;
		} catch (NoSuchMethodError nsme) {
			nsme.printStackTrace();
			return null;
		} finally {
		    if(collection != null)
		        collection.releaseDocument(doc);
			brokerPool.release(broker);
		}
	}

	public byte[] getBinaryResource(User user, String name)
			throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			doc = (DocumentImpl) broker.openDocument(name, Lock.READ_LOCK);
			if (doc == null)
				throw new EXistException("Resource " + name + " not found");
			if (doc.getResourceType() != DocumentImpl.BINARY_FILE)
				throw new EXistException("Document " + name
						+ " is not a binary resource");
			if(!doc.getPermissions().validate(user, Permission.READ))
			    throw new PermissionDeniedException("Insufficient privileges to read resource");
			return broker.getBinaryResourceData((BinaryDocument) doc);
		} finally {
			if(doc != null)
				doc.getUpdateLock().release(Lock.READ_LOCK);
			brokerPool.release(broker);
		}
	}

	public int xupdate(User user, String collectionName, String xupdate)
			throws SAXException, LockException, PermissionDeniedException, EXistException,
			XPathException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			Collection collection = broker.getCollection(collectionName);
			if (collection == null)
				throw new EXistException("collection " + collectionName
						+ " not found");
			DocumentSet docs = collection.allDocs(broker, new DocumentSet(),
					true, true);
			XUpdateProcessor processor = new XUpdateProcessor(broker, docs);
			Modification modifications[] = processor.parse(new InputSource(
					new StringReader(xupdate)));
			long mods = 0;
			for (int i = 0; i < modifications.length; i++) {
				mods += modifications[i].process();
				broker.flush();
			}
			return (int) mods;
		} catch (ParserConfigurationException e) {
			throw new EXistException(e.getMessage());
		} catch (IOException e) {
			throw new EXistException(e.getMessage());
		} finally {
			brokerPool.release(broker);
		}
	}

	public int xupdateResource(User user, String resource, String xupdate)
			throws SAXException, LockException, PermissionDeniedException, EXistException,
			XPathException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			DocumentImpl doc = (DocumentImpl)broker.getDocument(resource);
			if (doc == null)
				throw new EXistException("document " + resource + " not found");
			if(!doc.getPermissions().validate(user, Permission.READ))
			    throw new PermissionDeniedException("Insufficient privileges to read resource");
			DocumentSet docs = new DocumentSet();
			docs.add(doc);
			XUpdateProcessor processor = new XUpdateProcessor(broker, docs);
			Modification modifications[] = processor.parse(new InputSource(
					new StringReader(xupdate)));
			long mods = 0;
			for (int i = 0; i < modifications.length; i++) {
				mods += modifications[i].process();
				broker.flush();
			}
			return (int) mods;
		} catch (ParserConfigurationException e) {
			throw new EXistException(e.getMessage());
		} catch (IOException e) {
			throw new EXistException(e.getMessage());
		} finally {
			brokerPool.release(broker);
		}
	}

	public boolean sync() {
		DBBroker broker = null;
		try {
			broker = brokerPool.get();
			broker.sync(Sync.MAJOR_SYNC);
		} catch (EXistException e) {
		} finally {
			brokerPool.release(broker);
		}
		return true;
	}

	public Vector getDocumentListing(User user) throws EXistException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			DocumentSet docs = broker.getAllDocuments(new DocumentSet());
			String names[] = docs.getNames();
			Vector vec = new Vector();
			for (int i = 0; i < names.length; i++)
				vec.addElement(names[i]);

			return vec;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Vector getDocumentListing(User user, String name)
			throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			if (!name.startsWith("/"))
				name = '/' + name;
			if (!name.startsWith("/db"))
				name = "/db" + name;
			collection = broker.openCollection(name, Lock.READ_LOCK);
			Vector vec = new Vector();
			if (collection == null) {
			    LOG.debug("collection " + name + " not found.");
				return vec;
			}
			String resource;
			int p;
			for (Iterator i = collection.iterator(broker); i.hasNext(); ) {
				resource = ((DocumentImpl) i.next()).getFileName();
				p = resource.lastIndexOf('/');
				vec.addElement(p < 0 ? resource : resource.substring(p + 1));
			}
			return vec;
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public int getResourceCount(User user, String collectionName)
	throws EXistException, PermissionDeniedException {
	    DBBroker broker = null;
	    Collection collection = null;
		try {
			broker = brokerPool.get(user);
			if (!collectionName.startsWith("/"))
				collectionName = '/' + collectionName;
			if (!collectionName.startsWith("/db"))
				collectionName = "/db" + collectionName;
			collection = broker.openCollection(collectionName, Lock.READ_LOCK);
			return collection.getDocumentCount();
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}
	
	public String createResourceId(User user, String collectionName)
	throws EXistException, PermissionDeniedException {
	    DBBroker broker = null;
	    Collection collection = null;
	    try {
	        broker = brokerPool.get(user);
	        if (!collectionName.startsWith("/"))
	            collectionName = '/' + collectionName;
	        if (!collectionName.startsWith("/db"))
	            collectionName = "/db" + collectionName;
	        collection = broker.openCollection(collectionName, Lock.READ_LOCK);
	        String id;
			Random rand = new Random();
			boolean ok;
			do {
				ok = true;
				id = Integer.toHexString(rand.nextInt()) + ".xml";
				// check if this id does already exist
				if (collection.hasDocument(id))
					ok = false;

				if (collection.hasSubcollection(id))
					ok = false;

			} while (!ok);
			return id;
	    } finally {
	    	if(collection != null)
				collection.release();
	        brokerPool.release(broker);
	    }
	}
	
	public Hashtable listDocumentPermissions(User user, String name)
			throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			if (!name.startsWith("/"))
				name = '/' + name;
			if (!name.startsWith("/db"))
				name = "/db" + name;
			collection = broker.openCollection(name, Lock.READ_LOCK);
			if (collection == null)
				throw new EXistException("Collection " + name + " not found");
			if (!collection.getPermissions().validate(user, Permission.READ))
				throw new PermissionDeniedException(
						"not allowed to read collection " + name);
			Hashtable result = new Hashtable(collection.getDocumentCount());
			DocumentImpl doc;
			Permission perm;
			Vector tmp;
			String docName;
			for (Iterator i = collection.iterator(broker); i.hasNext(); ) {
				doc = (DocumentImpl) i.next();
				perm = doc.getPermissions();
				tmp = new Vector(3);
				tmp.addElement(perm.getOwner());
				tmp.addElement(perm.getOwnerGroup());
				tmp.addElement(new Integer(perm.getPermissions()));
				docName = doc.getFileName();
				result.put(docName, tmp);
			}
			return result;
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public Hashtable listCollectionPermissions(User user, String name)
			throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			if (!name.startsWith("/"))
				name = '/' + name;
			if (!name.startsWith("/db"))
				name = "/db" + name;
			collection = broker.openCollection(name, Lock.READ_LOCK);
			if (collection == null)
				throw new EXistException("Collection " + name + " not found");
			if (!collection.getPermissions().validate(user, Permission.READ))
				throw new PermissionDeniedException(
						"not allowed to read collection " + name);
			Hashtable result = new Hashtable(collection
					.getChildCollectionCount());
			String child, path;
			Collection childColl;
			Permission perm;
			Vector tmp;
			for (Iterator i = collection.collectionIterator(); i.hasNext(); ) {
				child = (String) i.next();
				path = name + '/' + child;
				childColl = broker.getCollection(path);
				perm = childColl.getPermissions();
				tmp = new Vector(3);
				tmp.addElement(perm.getOwner());
				tmp.addElement(perm.getOwnerGroup());
				tmp.addElement(new Integer(perm.getPermissions()));
				result.put(child, tmp);
			}
			return result;
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public int getHits(User user, int resultId) throws EXistException {
		QueryResult qr = (QueryResult) connectionPool.resultSets.get(resultId);
		if (qr == null)
			throw new EXistException("result set unknown or timed out");
		qr.timestamp = System.currentTimeMillis();
		if (qr.result == null)
			return 0;
		return qr.result.getLength();
	}

	public Hashtable getPermissions(User user, String name)
			throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (!name.startsWith("/"))
				name = '/' + name;
			if (!name.startsWith("/db"))
				name = "/db" + name;
			Collection collection = broker.openCollection(name, Lock.READ_LOCK);
			Permission perm = null;
			if (collection == null) {
				DocumentImpl doc = (DocumentImpl) broker.openDocument(name, Lock.READ_LOCK);
				if (doc == null)
					throw new EXistException("document or collection " + name
							+ " not found");
				perm = doc.getPermissions();
				doc.getUpdateLock().release(Lock.READ_LOCK);
			} else {
				perm = collection.getPermissions();
				collection.release();
			}
			Hashtable result = new Hashtable();
			result.put("owner", perm.getOwner());
			result.put("group", perm.getOwnerGroup());
			result.put("permissions", new Integer(perm.getPermissions()));
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Date getCreationDate(User user, String collectionPath)
			throws PermissionDeniedException, EXistException {
		DBBroker broker = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			if (!collectionPath.startsWith("/"))
				collectionPath = '/' + collectionPath;
			if (!collectionPath.startsWith("/db"))
				collectionPath = "/db" + collectionPath;
			collection = broker.openCollection(collectionPath, Lock.READ_LOCK);
			if (collection == null)
				throw new EXistException("collection " + collectionPath
						+ " not found");
			return new Date(collection.getCreationTime());
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public Vector getTimestamps(User user, String documentPath)
			throws PermissionDeniedException, EXistException {
		DBBroker broker = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			if (!documentPath.startsWith("/"))
				documentPath = '/' + documentPath;
			if (!documentPath.startsWith("/db"))
				documentPath = "/db" + documentPath;
			doc = (DocumentImpl) broker.openDocument(documentPath, Lock.READ_LOCK);
			if (doc == null) {
				LOG.debug("document " + documentPath + " not found!");
				throw new EXistException("document not found");
			}
			Vector vector = new Vector(2);
			vector.addElement(new Date(doc.getCreated()));
			vector.addElement(new Date(doc.getLastModified()));
			return vector;
		} finally {
			if(doc != null)
				doc.getUpdateLock().release(Lock.READ_LOCK);
			brokerPool.release(broker);
		}
	}

	public Hashtable getUser(User user, String name) throws EXistException,
			PermissionDeniedException {
		User u = brokerPool.getSecurityManager().getUser(name);
		if (u == null)
			throw new EXistException("user " + name + " does not exist");
		Hashtable tab = new Hashtable();
		tab.put("name", u.getName());
		Vector groups = new Vector();
		for (Iterator i = u.getGroups(); i.hasNext(); )
			groups.addElement(i.next());
		tab.put("groups", groups);
		if (u.getHome() != null)
			tab.put("home", u.getHome());
		return tab;
	}

	public Vector getUsers(User user) throws EXistException,
			PermissionDeniedException {
		User users[] = brokerPool.getSecurityManager().getUsers();
		Vector r = new Vector();
		for (int i = 0; i < users.length; i++) {
			final Hashtable tab = new Hashtable();
			tab.put("name", users[i].getName());
			Vector groups = new Vector();
			for (Iterator j = users[i].getGroups(); j.hasNext(); )
				groups.addElement(j.next());
			tab.put("groups", groups);
			if (users[i].getHome() != null)
				tab.put("home", users[i].getHome());
			r.addElement(tab);
		}
		return r;
	}

	public Vector getGroups(User user) throws EXistException,
			PermissionDeniedException {
		String[] groups = brokerPool.getSecurityManager().getGroups();
		Vector v = new Vector(groups.length);
		for (int i = 0; i < groups.length; i++) {
			v.addElement(groups[i]);
		}
		return v;
	}

	public boolean hasDocument(User user, String name) throws Exception {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			return (broker.getDocument(name) != null);
		} finally {
			brokerPool.release(broker);
		}
	}

	public boolean parse(User user, byte[] xml, String path, 
			boolean replace) throws Exception {
		DBBroker broker = null;
		Collection collection = null;
		try {
			long startTime = System.currentTimeMillis();
			broker = brokerPool.get(user);
			int p = path.lastIndexOf('/');
			if (p < 0 || p == path.length() - 1)
				throw new EXistException("Illegal document path");
			String collectionName = path.substring(0, p);
			String docName = path.substring(p + 1);
			InputSource source;
			IndexInfo info;
			try {
				collection = broker.openCollection(collectionName, Lock.WRITE_LOCK);
				if (collection == null)
					throw new EXistException("Collection " + collectionName
							+ " not found");
				if (!replace) {
					DocumentImpl old = collection.getDocument(broker, docName);
					if (old != null)
						throw new PermissionDeniedException(
								"Document exists and overwrite is not allowed");
				}
				InputStream is = new ByteArrayInputStream(xml);
				source = new InputSource(is);
				info = collection.validate(broker, docName, source);
			} finally {
				if(collection != null)
					collection.release();
			}
			collection.store(broker, info, source, false);
			LOG.debug("parsing " + path + " took "
					+ (System.currentTimeMillis() - startTime) + "ms.");
			documentCache.clear();
			return true;
		} catch (Exception e) {
			LOG.debug(e.getMessage(), e);
			throw e;
		} finally {
			brokerPool.release(broker);
		}
	}

	/**
	 * Parse a file previously uploaded with upload.
	 * 
	 * The temporary file will be removed.
	 * 
	 * @param user
	 * @param localFile
	 * @throws EXistException
	 * @throws IOException
	 */
	public boolean parseLocal(User user, String localFile, String docName,
			boolean replace) throws EXistException, PermissionDeniedException, LockException,
			SAXException, TriggerException {
		File file = new File(localFile);
		if (!file.canRead())
			throw new EXistException("unable to read file " + localFile);
		DBBroker broker = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			int p = docName.lastIndexOf('/');
			if (p < 0 || p == docName.length() - 1)
				throw new EXistException("Illegal document path");
			String collectionName = docName.substring(0, p);
			docName = docName.substring(p + 1);
			Collection collection = null;
			IndexInfo info;
			InputSource source;
			try {
				collection = broker.openCollection(collectionName, Lock.WRITE_LOCK);
				if (collection == null)
					throw new EXistException("Collection " + collectionName
							+ " not found");
				if (!replace) {
					DocumentImpl old = collection.getDocument(broker, docName);
					if (old != null)
						throw new PermissionDeniedException(
								"Old document exists and overwrite is not allowed");
				}
				source = new InputSource(file.toURI().toASCIIString());
				info = collection.validate(broker, docName, source);
			} finally {
				if(collection != null)
					collection.release();
			}
			collection.store(broker, info, source, false);
		} finally {
			brokerPool.release(broker);
		}
		file.delete();
		documentCache.clear();
		return doc != null;
	}

	public boolean storeBinary(User user, byte[] data, String docName,
		boolean replace) throws EXistException, PermissionDeniedException, LockException {
		DBBroker broker = null;
		DocumentImpl doc = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			int p = docName.lastIndexOf('/');
			if (p < 0 || p == docName.length() - 1)
				throw new EXistException("Illegal document path");
			String collectionName = docName.substring(0, p);
			docName = docName.substring(p + 1);
			collection = broker.openCollection(collectionName, Lock.WRITE_LOCK);
			if (collection == null)
				throw new EXistException("Collection " + collectionName
						+ " not found");
			if (!replace) {
				DocumentImpl old = collection.getDocument(broker, docName);
				if (old != null)
					throw new PermissionDeniedException(
							"Old document exists and overwrite is not allowed");
			}
			doc = collection.addBinaryResource(broker, docName, data);
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
		documentCache.clear();
		return doc != null;
	}

	public String upload(User user, byte[] chunk, int length, String fileName)
			throws EXistException, IOException {
		File file;
		if (fileName == null || fileName.length() == 0) {
			// create temporary file
			file = File.createTempFile("rpc", "xml");
			fileName = file.getAbsolutePath();
			LOG.debug("created temporary file " + file.getAbsolutePath());
		} else {
			LOG.debug("appending to file " + fileName);
			file = new File(fileName);
		}
		if (!file.canWrite())
			throw new EXistException("cannot write to file " + fileName);
		FileOutputStream os = new FileOutputStream(file.getAbsolutePath(), true);
		os.write(chunk, 0, length);
		os.close();
		return fileName;
	}

	protected String printAll(DBBroker broker, Sequence resultSet, int howmany,
			int start, Hashtable properties, long queryTime) throws Exception {
		if (resultSet.getLength() == 0)
			return "<?xml version=\"1.0\"?>\n"
					+ "<exist:result xmlns:exist=\"http://exist.sourceforge.net/NS/exist\" "
					+ "hitCount=\"0\"/>";
		if (howmany > resultSet.getLength() || howmany == 0)
			howmany = resultSet.getLength();

		if (start < 1 || start > resultSet.getLength())
			throw new EXistException("start parameter out of range");

		StringWriter writer = new StringWriter();
		writer.write("<exist:result xmlns:exist=\"");
		writer.write(EXIST_NS);
		writer.write("\" hits=\"");
		writer.write(Integer.toString(resultSet.getLength()));
		writer.write("\" start=\"");
		writer.write(Integer.toString(start));
		writer.write("\" count=\"");
		writer.write(Integer.toString(howmany));
		writer.write("\">\n");

		Serializer serializer = broker.getSerializer();
		serializer.reset();
		serializer.setProperties(properties);

		Item item;
		for (int i = --start; i < start + howmany; i++) {
			item = resultSet.itemAt(i);
			if (item == null)
				continue;
			if (item.getType() == Type.ELEMENT) {
				NodeValue node = (NodeValue) item;
				writer.write(serializer.serialize(node));
			} else {
				writer.write("<exist:value type=\"");
				writer.write(Type.getTypeName(item.getType()));
				writer.write("\">");
				writer.write(item.getStringValue());
				writer.write("</exist:value>");
			}
		}
		writer.write("\n</exist:result>");
		return writer.toString();
	}

	public String query(User user, String xpath, int howmany, int start,
			Hashtable parameters) throws Exception {
		long startTime = System.currentTimeMillis();
		String result;
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			QueryResult qr = doQuery(user, broker, xpath, null, parameters);
			if (qr.hasErrors())
				throw qr.getException();
			if (qr == null)
				return "<?xml version=\"1.0\"?>\n"
						+ "<exist:result xmlns:exist=\"http://exist.sourceforge.net/NS/exist\" "
						+ "hitCount=\"0\"/>";

			result = printAll(broker, qr.result, howmany, start, parameters,
					(System.currentTimeMillis() - startTime));
		} finally {
			brokerPool.release(broker);
		}
		return result;
	}

	public Hashtable queryP(User user, String xpath, String docName,
			String s_id, Hashtable parameters) throws Exception {
		long startTime = System.currentTimeMillis();
		String sortBy = (String) parameters.get(RpcAPI.SORT_EXPR);

		Hashtable ret = new Hashtable();
		Vector result = new Vector();
		NodeSet nodes = null;
		QueryResult queryResult;
		Sequence resultSeq = null;
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			if (docName != null && s_id != null) {
				DocumentImpl doc;
				if (!documentCache.containsKey(docName)) {
					doc = (DocumentImpl) broker.getDocument(docName);
					documentCache.put(docName, doc);
				} else
					doc = (DocumentImpl) documentCache.get(docName);
				Vector docs = new Vector(1);
				docs.addElement(docName);
				parameters.put(RpcAPI.STATIC_DOCUMENTS, docs);
				
				if(s_id.length() > 0) {
					long id = Long.parseLong(s_id);
					NodeProxy node = new NodeProxy(doc, id);
					nodes = new ArraySet(1);
					nodes.add(node);
				}
			}
			queryResult = doQuery(user, broker, xpath, nodes, parameters);
			if (queryResult == null)
				return ret;
			if (queryResult.hasErrors()) {
				// return an error description
				XPathException e = queryResult.getException();
				ret.put(RpcAPI.ERROR, e.getMessage());
				if(e.getLine() != 0) {
					ret.put(RpcAPI.LINE, new Integer(e.getLine()));
					ret.put(RpcAPI.COLUMN, new Integer(e.getColumn()));
				}
				return ret;
			}
			resultSeq = queryResult.result;
			LOG.debug("found " + resultSeq.getLength());
			
			if (sortBy != null) {
				SortedNodeSet sorted = new SortedNodeSet(brokerPool, user,
						sortBy);
				sorted.addAll(resultSeq);
				resultSeq = sorted;
			}
			NodeProxy p;
			Vector entry;
			if (resultSeq != null) {
				SequenceIterator i = resultSeq.iterate();
				if (i != null) {
					Item next;
					while (i.hasNext()) {
						next = i.nextItem();
						if (Type.subTypeOf(next.getType(), Type.NODE)) {
							entry = new Vector();
							if (((NodeValue) next).getImplementationType() == NodeValue.PERSISTENT_NODE) {
								p = (NodeProxy) next;
								entry.addElement(p.getDocument().getCollection().getName() + '/' + p.getDocument().getFileName());
								entry.addElement(Long.toString(p.getGID()));
							} else {
								entry.addElement("temp_xquery/"
										+ next.hashCode());
								entry.addElement(String
										.valueOf(((NodeImpl) next)
												.getNodeNumber()));
							}
							result.addElement(entry);
						} else
							result.addElement(next.getStringValue());
					}
				} else {
					LOG.debug("sequence iterator is null. Should not");
				}
			} else
				LOG.debug("result sequence is null. Skipping it...");
		} finally {
			brokerPool.release(broker);
		}
		queryResult.result = resultSeq;
		queryResult.queryTime = (System.currentTimeMillis() - startTime);
		connectionPool.resultSets.put(queryResult.hashCode(), queryResult);
		ret.put("id", new Integer(queryResult.hashCode()));
		ret.put("results", result);
		return ret;
	}

	public void releaseQueryResult(int handle) {
		connectionPool.resultSets.remove(handle);
		documentCache.clear();
		LOG.debug("removed query result with handle " + handle);
	}

	public void remove(User user, String docPath) throws Exception {
		DBBroker broker = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			int p = docPath.lastIndexOf('/');
			if (p < 0 || p == docPath.length() - 1)
				throw new EXistException("Illegal document path");
			String collectionName = docPath.substring(0, p);
			String docName = docPath.substring(p + 1);
			collection = broker.openCollection(collectionName, Lock.WRITE_LOCK);
			if (collection == null)
				throw new EXistException("Collection " + collectionName
						+ " not found");
			DocumentImpl doc = collection.getDocument(broker, docName);
			if(doc == null)
				throw new EXistException("Document " + docPath + " not found");
			if(doc.getResourceType() == DocumentImpl.BINARY_FILE)
				collection.removeBinaryResource(broker, doc);
			else
				collection.removeDocument(broker, docName);
			documentCache.clear();
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public boolean removeCollection(User user, String name) throws Exception {
		DBBroker broker = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			collection = broker.openCollection(name, Lock.WRITE_LOCK);
			if (collection == null)
				return false;
			LOG.debug("removing collection " + name);
			documentCache.clear();
			return broker.removeCollection(collection);
		} finally {
			if(collection != null)
				collection.getLock().release();
			brokerPool.release(broker);
		}
	}

	public boolean removeUser(User user, String name) throws EXistException,
			PermissionDeniedException {
		org.exist.security.SecurityManager manager = brokerPool
				.getSecurityManager();
		if (!manager.hasAdminPrivileges(user))
			throw new PermissionDeniedException(
					"you are not allowed to remove users");

		manager.deleteUser(name);
		return true;
	}

	public String retrieve(User user, String docName, String s_id,
			Hashtable parameters) throws Exception {
		DBBroker broker = brokerPool.get(user);
		try {
			long id = Long.parseLong(s_id);
			DocumentImpl doc;
			if (!documentCache.containsKey(docName)) {
				LOG.debug("loading doc " + docName);
				doc = (DocumentImpl) broker.getDocument(docName);
				documentCache.put(docName, doc);
			} else
				doc = (DocumentImpl) documentCache.get(docName);

			NodeProxy node = new NodeProxy(doc, id);
			Serializer serializer = broker.getSerializer();
			serializer.reset();
			serializer.setProperties(parameters);
			return serializer.serialize(node);
		} finally {
			brokerPool.release(broker);
		}
	}

	public String retrieve(User user, int resultId, int num,
			Hashtable parameters) throws Exception {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			QueryResult qr = (QueryResult) connectionPool.resultSets
					.get(resultId);
			if (qr == null)
				throw new EXistException("result set unknown or timed out");
			qr.timestamp = System.currentTimeMillis();
			Item item = qr.result.itemAt(num);
			if (item == null)
				throw new EXistException("index out of range");

			if(Type.subTypeOf(item.getType(), Type.NODE)) {
			    NodeValue nodeValue = (NodeValue)item;
			    Serializer serializer = broker.getSerializer();
				serializer.reset();
				checkPragmas(qr.context, parameters);
				serializer.setProperties(parameters);
				return serializer.serialize(nodeValue);
			} else {
				return item.getStringValue();
			}
		} finally {
			brokerPool.release(broker);
		}
	}

	public String retrieveAll(User user, int resultId, Hashtable parameters) throws Exception {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			QueryResult qr = (QueryResult) connectionPool.resultSets
					.get(resultId);
			if (qr == null)
				throw new EXistException("result set unknown or timed out");
			qr.timestamp = System.currentTimeMillis();
			checkPragmas(qr.context, parameters);
			Serializer serializer = broker.getSerializer();
			serializer.reset();
			serializer.setProperties(parameters);
			
			SAXSerializer handler = SAXSerializerPool.getInstance().borrowSAXSerializer();
			handler.setOutputProperties(getProperties(parameters));
			StringWriter writer = new StringWriter();
			handler.setWriter(writer);
			
//			serialize results
			handler.startDocument();
			handler.startPrefixMapping("exist", Serializer.EXIST_NS);
			AttributesImpl attribs = new AttributesImpl();
			attribs.addAttribute(
				"",
				"hitCount",
				"hitCount",
				"CDATA",
				Integer.toString(qr.result.getLength()));
			handler.startElement(
						Serializer.EXIST_NS,
						"result",
						"exist:result",
						attribs);
			Item current;
			char[] value;
			for(SequenceIterator i = qr.result.iterate(); i.hasNext(); ) {
				current = i.nextItem();
				if(Type.subTypeOf(current.getType(), Type.NODE))
					((NodeValue)current).toSAX(broker, handler);
				else {
					value = current.toString().toCharArray();
					handler.characters(value, 0, value.length);
				}
			}
			handler.endElement(Serializer.EXIST_NS, "result", "exist:result");
			handler.endPrefixMapping("exist");
			handler.endDocument();
			return writer.toString();
		} finally {
			brokerPool.release(broker);
		}
	}
	
	public void run() {
		synchronized (this) {
			while (!terminate)
				try {
					this.wait(500);
				} catch (InterruptedException inte) {
				}

		}
		// broker.shutdown();
	}

	public boolean setPermissions(User user, String resource, String owner,
			String ownerGroup, String permissions) throws EXistException,
			PermissionDeniedException {
		DBBroker broker = null;
		Collection collection = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			org.exist.security.SecurityManager manager = brokerPool
					.getSecurityManager();
			collection = broker.openCollection(resource, Lock.WRITE_LOCK);
			if (collection == null) {
				doc = (DocumentImpl) broker.openDocument(resource, Lock.WRITE_LOCK);
				if (doc == null)
					throw new EXistException("document or collection "
							+ resource + " not found");
				LOG.debug("changing permissions on document " + resource);
				Permission perm = doc.getPermissions();
				if (perm.getOwner().equals(user.getName())
						|| manager.hasAdminPrivileges(user)) {
					if (owner != null) {
						perm.setOwner(owner);
						perm.setGroup(ownerGroup);
					}
					if (permissions != null && permissions.length() > 0)
						perm.setPermissions(permissions);
					broker.saveCollection(doc.getCollection());
					broker.flush();
					return true;
				} else
					throw new PermissionDeniedException(
							"not allowed to change permissions");
			} else {
				LOG.debug("changing permissions on collection " + resource);
				Permission perm = collection.getPermissions();
				if (perm.getOwner().equals(user.getName())
						|| manager.hasAdminPrivileges(user)) {
					if (permissions != null)
						perm.setPermissions(permissions);
					if (owner != null) {
						perm.setOwner(owner);
						perm.setGroup(ownerGroup);
					}
					broker.saveCollection(collection);
					broker.flush();
					return true;
				} else
					throw new PermissionDeniedException(
							"not allowed to change permissions");
			}
		} catch (SyntaxException e) {
			throw new EXistException(e.getMessage());
		} catch (PermissionDeniedException e) {
			throw new EXistException(e.getMessage());
		} finally {
			if(doc != null)
				doc.getUpdateLock().release(Lock.WRITE_LOCK);
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public boolean setPermissions(User user, String resource, String owner,
			String ownerGroup, int permissions) throws EXistException,
			PermissionDeniedException {
		DBBroker broker = null;
		Collection collection = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			org.exist.security.SecurityManager manager = brokerPool
					.getSecurityManager();
			collection = broker.openCollection(resource, Lock.WRITE_LOCK);
			if (collection == null) {
				doc = (DocumentImpl) broker.openDocument(resource, Lock.WRITE_LOCK);
				if (doc == null)
					throw new EXistException("document or collection "
							+ resource + " not found");
				LOG.debug("changing permissions on document " + resource);
				Permission perm = doc.getPermissions();
				if (perm.getOwner().equals(user.getName())
						|| manager.hasAdminPrivileges(user)) {
					if (owner != null) {
						perm.setOwner(owner);
						perm.setGroup(ownerGroup);
					}
					perm.setPermissions(permissions);
					broker.saveCollection(doc.getCollection());
					broker.flush();
					return true;
				} else
					throw new PermissionDeniedException(
							"not allowed to change permissions");
			} else {
				LOG.debug("changing permissions on collection " + resource);
				Permission perm = collection.getPermissions();
				if (perm.getOwner().equals(user.getName())
						|| manager.hasAdminPrivileges(user)) {
					perm.setPermissions(permissions);
					if (owner != null) {
						perm.setOwner(owner);
						perm.setGroup(ownerGroup);
					}
					broker.saveCollection(collection);
					broker.flush();
					return true;
				} else
					throw new PermissionDeniedException(
							"not allowed to change permissions");
			}
		} catch (PermissionDeniedException e) {
			throw new EXistException(e.getMessage());
		} finally {
			if(doc != null)
				doc.getUpdateLock().release(Lock.WRITE_LOCK);
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public boolean setUser(User user, String name, String passwd,
			Vector groups, String home) throws EXistException,
			PermissionDeniedException {
		org.exist.security.SecurityManager manager = brokerPool
				.getSecurityManager();
		if(name.equals(org.exist.security.SecurityManager.GUEST_USER) &&
				(!manager.hasAdminPrivileges(user)))
			throw new PermissionDeniedException(
				"guest user cannot be modified");
		User u;
		if (!manager.hasUser(name)) {
			if (!manager.hasAdminPrivileges(user))
				throw new PermissionDeniedException(
						"not allowed to create user");
			u = new User(name);
			u.setPasswordDigest(passwd);
		} else {
			u = manager.getUser(name);
			if (!(u.getName().equals(user.getName()) || manager
					.hasAdminPrivileges(user)))
				throw new PermissionDeniedException(
						"you are not allowed to change this user");
			u.setPasswordDigest(passwd);
		}
		String g;
		for (Iterator i = groups.iterator(); i.hasNext(); ) {
			g = (String) i.next();
			if (!u.hasGroup(g)) {
				if(!manager.hasAdminPrivileges(user))
					throw new PermissionDeniedException(
							"User is not allowed to add groups");
				u.addGroup(g);
			}
		}
		if (home != null)
			u.setHome(home);
		manager.setUser(u);
		return true;
	}

	public boolean lockResource(User user, String path, String userName) throws Exception {
		DBBroker broker = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			doc = (DocumentImpl) broker.openDocument(path, Lock.WRITE_LOCK);
				if (doc == null)
					throw new EXistException("Resource "
							+ path + " not found");
			if (!doc.getPermissions().validate(user, Permission.UPDATE))
				throw new PermissionDeniedException("User is not allowed to lock resource " + path);
			org.exist.security.SecurityManager manager = brokerPool.getSecurityManager();
			if (!(userName.equals(user.getName()) || manager.hasAdminPrivileges(user)))
				throw new PermissionDeniedException("User " + user.getName() + " is not allowed " +
						"to lock the resource for user " + userName);
			User lockOwner = doc.getUserLock();
			if(lockOwner != null && (!lockOwner.equals(user)) && (!manager.hasAdminPrivileges(user)))
				throw new PermissionDeniedException("Resource is already locked by user " +
						lockOwner.getName());
			doc.setUserLock(user);
			broker.saveCollection(doc.getCollection());
			return true;
		} finally {
			if(doc != null)
				doc.getUpdateLock().release(Lock.WRITE_LOCK);
			brokerPool.release(broker);
		}
	}
	
	public String hasUserLock(User user, String path) throws Exception {
		DBBroker broker = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			doc = (DocumentImpl) broker.openDocument(path, Lock.READ_LOCK);
			if(!doc.getPermissions().validate(user, Permission.READ))
			    throw new PermissionDeniedException("Insufficient privileges to read resource");
			if (doc == null)
				throw new EXistException("Resource " + path + " not found");
			User u = doc.getUserLock();
			return u == null ? "" : u.getName();
		} finally {
			if(doc != null)
				doc.getUpdateLock().release(Lock.READ_LOCK);
			brokerPool.release(broker);
		}
	}
	
	public boolean unlockResource(User user, String path) throws Exception {
		DBBroker broker = null;
		DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			doc = (DocumentImpl) broker.openDocument(path, Lock.WRITE_LOCK);
			if (doc == null)
				throw new EXistException("Resource "
						+ path + " not found");
			if (!doc.getPermissions().validate(user, Permission.UPDATE))
				throw new PermissionDeniedException("User is not allowed to lock resource " + path);
			org.exist.security.SecurityManager manager = brokerPool.getSecurityManager();
			User lockOwner = doc.getUserLock();
			if(lockOwner != null && (!lockOwner.equals(user)) && (!manager.hasAdminPrivileges(user)))
				throw new PermissionDeniedException("Resource is already locked by user " +
						lockOwner.getName());
			doc.setUserLock(null);
			broker.saveCollection(doc.getCollection());
			return true;
		} finally {
			if(doc != null)
				doc.getUpdateLock().release(Lock.WRITE_LOCK);
			brokerPool.release(broker);
		}
	}
	
	public Hashtable summary(User user, String xpath) throws Exception {
		long startTime = System.currentTimeMillis();
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			QueryResult qr = doQuery(user, broker, xpath, null, null);
			if (qr == null)
				return new Hashtable();
			if (qr.hasErrors())
				throw qr.getException();
			NodeList resultSet = (NodeList) qr.result;
			HashMap map = new HashMap();
			HashMap doctypes = new HashMap();
			NodeProxy p;
			String docName;
			DocumentType doctype;
			NodeCount counter;
			DoctypeCount doctypeCounter;
			for (Iterator i = ((NodeSet) resultSet).iterator(); i.hasNext(); ) {
				p = (NodeProxy) i.next();
				docName = p.getDocument().getCollection().getName() + '/' + p.getDocument().getFileName();
				doctype = p.getDocument().getDoctype();
				if (map.containsKey(docName)) {
					counter = (NodeCount) map.get(docName);
					counter.inc();
				} else {
					counter = new NodeCount(p.getDocument());
					map.put(docName, counter);
				}
				if (doctype == null)
					continue;
				if (doctypes.containsKey(doctype.getName())) {
					doctypeCounter = (DoctypeCount) doctypes.get(doctype
							.getName());
					doctypeCounter.inc();
				} else {
					doctypeCounter = new DoctypeCount(doctype);
					doctypes.put(doctype.getName(), doctypeCounter);
				}
			}
			Hashtable result = new Hashtable();
			result.put("queryTime", new Integer((int) (System
					.currentTimeMillis() - startTime)));
			result.put("hits", new Integer(resultSet.getLength()));
			Vector documents = new Vector();
			Vector hitsByDoc;
			for (Iterator i = map.values().iterator(); i.hasNext(); ) {
				counter = (NodeCount) i.next();
				hitsByDoc = new Vector();
				hitsByDoc.addElement(counter.doc.getFileName());
				hitsByDoc.addElement(new Integer(counter.doc.getDocId()));
				hitsByDoc.addElement(new Integer(counter.count));
				documents.addElement(hitsByDoc);
			}
			result.put("documents", documents);
			Vector dtypes = new Vector();
			Vector hitsByType;
			DoctypeCount docTemp;
			for (Iterator i = doctypes.values().iterator(); i.hasNext(); ) {
				docTemp = (DoctypeCount) i.next();
				hitsByType = new Vector();
				hitsByType.addElement(docTemp.doctype.getName());
				hitsByType.addElement(new Integer(docTemp.count));
				dtypes.addElement(hitsByType);
			}
			result.put("doctypes", dtypes);
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Hashtable summary(User user, int resultId) throws EXistException {
		long startTime = System.currentTimeMillis();
		QueryResult qr = (QueryResult) connectionPool.resultSets.get(resultId);
		if (qr == null)
			throw new EXistException("result set unknown or timed out");
		qr.timestamp = System.currentTimeMillis();
		Hashtable result = new Hashtable();
		result.put("queryTime", new Integer((int) qr.queryTime));
		if (qr.result == null) {
			result.put("hits", new Integer(0));
			return result;
		}
		DBBroker broker = brokerPool.get(user);
		try {
			NodeList resultSet = (NodeList) qr.result;
			HashMap map = new HashMap();
			HashMap doctypes = new HashMap();
			NodeProxy p;
			String docName;
			DocumentType doctype;
			NodeCount counter;
			DoctypeCount doctypeCounter;
			for (Iterator i = ((NodeSet) resultSet).iterator(); i.hasNext(); ) {
				p = (NodeProxy) i.next();
				docName = p.getDocument().getCollection().getName() + '/' + p.getDocument().getFileName();
				doctype = p.getDocument().getDoctype();
				if (map.containsKey(docName)) {
					counter = (NodeCount) map.get(docName);
					counter.inc();
				} else {
					counter = new NodeCount(p.getDocument());
					map.put(docName, counter);
				}
				if (doctype == null)
					continue;
				if (doctypes.containsKey(doctype.getName())) {
					doctypeCounter = (DoctypeCount) doctypes.get(doctype
							.getName());
					doctypeCounter.inc();
				} else {
					doctypeCounter = new DoctypeCount(doctype);
					doctypes.put(doctype.getName(), doctypeCounter);
				}
			}
			result.put("hits", new Integer(resultSet.getLength()));
			Vector documents = new Vector();
			Vector hitsByDoc;
			for (Iterator i = map.values().iterator(); i.hasNext(); ) {
				counter = (NodeCount) i.next();
				hitsByDoc = new Vector();
				hitsByDoc.addElement(counter.doc.getFileName());
				hitsByDoc.addElement(new Integer(counter.doc.getDocId()));
				hitsByDoc.addElement(new Integer(counter.count));
				documents.addElement(hitsByDoc);
			}
			result.put("documents", documents);
			Vector dtypes = new Vector();
			Vector hitsByType;
			DoctypeCount docTemp;
			for (Iterator i = doctypes.values().iterator(); i.hasNext(); ) {
				docTemp = (DoctypeCount) i.next();
				hitsByType = new Vector();
				hitsByType.addElement(docTemp.doctype.getName());
				hitsByType.addElement(new Integer(docTemp.count));
				dtypes.addElement(hitsByType);
			}
			result.put("doctypes", dtypes);
			return result;
		} finally {
			brokerPool.release(broker);
		}
	}

	public Vector getIndexedElements(User user, String collectionName,
			boolean inclusive) throws EXistException, PermissionDeniedException {
		DBBroker broker = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			collection = broker.openCollection(collectionName, Lock.READ_LOCK);
			if (collection == null)
				throw new EXistException("collection " + collectionName
						+ " not found");
			Occurrences occurrences[] = broker.getElementIndex().scanIndexedElements(collection,
					inclusive);
			Vector result = new Vector(occurrences.length);
			for (int i = 0; i < occurrences.length; i++) {
				QName qname = (QName)occurrences[i].getTerm();
				Vector temp = new Vector(4);
				temp.addElement(qname.getLocalName());
				temp.addElement(qname.getNamespaceURI());
				temp.addElement(qname.getPrefix() == null ? "" : qname.getPrefix());
				temp.addElement(new Integer(occurrences[i].getOccurrences()));
				result.addElement(temp);
			}
			return result;
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public Vector scanIndexTerms(User user, String collectionName,
			String start, String end, boolean inclusive)
			throws PermissionDeniedException, EXistException {
		DBBroker broker = null;
		Collection collection = null;
		try {
			broker = brokerPool.get(user);
			collection = broker.openCollection(collectionName, Lock.READ_LOCK);
			if (collection == null)
				throw new EXistException("collection " + collectionName
						+ " not found");
			Occurrences occurrences[] = broker.getTextEngine().scanIndexTerms(
					user, collection, start, end, inclusive);
			Vector result = new Vector(occurrences.length);
			Vector temp;
			for (int i = 0; i < occurrences.length; i++) {
				temp = new Vector(2);
				temp.addElement(occurrences[i].getTerm().toString());
				temp.addElement(new Integer(occurrences[i].getOccurrences()));
				result.addElement(temp);
			}
			return result;
		} finally {
			if(collection != null)
				collection.release();
			brokerPool.release(broker);
		}
	}

	public void synchronize() {
		documentCache.clear();
	}

	public void terminate() {
		terminate = true;
	}

	private Properties getProperties(Hashtable parameters) {
		Properties properties = new Properties();
		for (Iterator i = parameters.entrySet().iterator(); i.hasNext(); ) {
			Map.Entry entry = (Map.Entry) i.next();
			properties.setProperty((String) entry.getKey(), entry.getValue().toString());
		}
		return properties;
	}

	private CachedQuery getCachedQuery(String query) {
		CachedQuery found = null;
		CachedQuery cached;
		for (Iterator i = cachedExpressions.iterator(); i.hasNext(); ) {
			cached = (CachedQuery) i.next();
			if (cached.queryString.equals(query)) {
				found = cached;
				found.expression.reset();
				cached.timestamp = System.currentTimeMillis();
			} else {
				// timeout: release the compiled expression
				if (System.currentTimeMillis() - cached.timestamp > 120000) {
					LOG.debug("Releasing compiled expression");
					i.remove();
				}
			}
		}
		return found;
	}

	class CachedQuery {

		PathExpr expression;
		String queryString;
		long timestamp;

		public CachedQuery(PathExpr expr, String query) {
			this.expression = expr;
			this.queryString = query;
			this.timestamp = System.currentTimeMillis();
		}
	}

	class DoctypeCount {
		int count = 1;
		DocumentType doctype;

		/**
		 * Constructor for the DoctypeCount object
		 * 
		 * @param doctype
		 *                   Description of the Parameter
		 */
		public DoctypeCount(DocumentType doctype) {
			this.doctype = doctype;
		}

		public void inc() {
			count++;
		}
	}

	class NodeCount {
		int count = 1;
		DocumentImpl doc;

		/**
		 * Constructor for the NodeCount object
		 * 
		 * @param doc
		 *                   Description of the Parameter
		 */
		public NodeCount(DocumentImpl doc) {
			this.doc = doc;
		}

		public void inc() {
			count++;
		}
	}

//	FIXME: Check it for possible security hole. Check name.
	public byte[] getDocumentChunk(User user, String name, int start, int len)
			throws EXistException, PermissionDeniedException, IOException {
		File file = new File(System.getProperty("java.io.tmpdir")
				+ File.separator + name);
		if (!file.canRead())
			throw new EXistException("unable to read file " + name);
		if (file.length() < start+len)
			throw new EXistException("address too big " + name);
		byte buffer[] = new byte[len];
		RandomAccessFile os = new RandomAccessFile(file.getAbsolutePath(), "r");
		LOG.debug("Read from: " + start + " to: " + (start + len));
		os.seek(start);
		int reada = os.read(buffer);
		os.close();
		return buffer;
	}
	
	public boolean moveResource(User user, String docPath, String destinationPath, String newName) 
	throws EXistException, PermissionDeniedException {
	    DBBroker broker = null;
	    Collection collection = null;
	    Collection destination = null;
	    DocumentImpl doc = null;
		try {
			broker = brokerPool.get(user);
			// get source document
			int p = docPath.lastIndexOf('/');
			if (p < 0 || p == docPath.length() - 1)
				throw new EXistException("Illegal document path");
			String collectionName = docPath.substring(0, p);
			String docName = docPath.substring(p + 1);
			collection = broker.openCollection(collectionName, Lock.WRITE_LOCK);
			if (collection == null)
				throw new EXistException("Collection " + collectionName
						+ " not found");
			doc = collection.getDocumentWithLock(broker, docName, Lock.WRITE_LOCK);
			if(doc == null)
				throw new EXistException("Document " + docPath + " not found");
			
			// get destination collection
			destination = broker.openCollection(destinationPath, Lock.WRITE_LOCK);
			if(destination == null)
			    throw new EXistException("Destination collection " + destinationPath + " not found");
			broker.moveResource(doc, destination, newName);
			documentCache.clear();
			return true;
        } catch (LockException e) {
            throw new PermissionDeniedException("Could not acquire lock on document " + docPath);
        } finally {
        	if(collection != null)
        		collection.release();
        	if(destination != null)
        		destination.release();
        	if(doc != null)
        		doc.getUpdateLock().release(Lock.WRITE_LOCK);
			brokerPool.release(broker);
		}
	}
	
	public boolean moveCollection(User user, String collectionPath, String destinationPath, String newName) 
	throws EXistException, PermissionDeniedException {
	    DBBroker broker = null;
	    Collection collection = null;
	    Collection destination = null;
		try {
			broker = brokerPool.get(user);
			// get source document
			collection = broker.openCollection(collectionPath, Lock.WRITE_LOCK);
			if (collection == null)
				throw new EXistException("Collection " + collectionPath
						+ " not found");
			
			// get destination collection
			destination = broker.openCollection(destinationPath, Lock.WRITE_LOCK);
			if(destination == null)
			    throw new EXistException("Destination collection " + destinationPath + " not found");
			broker.moveCollection(collection, destination, newName);
			documentCache.clear();
			return true;
        } catch (LockException e) {
            throw new PermissionDeniedException(e.getMessage());
        } finally {
        	if(collection != null)
        		collection.release();
        	if(destination != null)
        		destination.release();
			brokerPool.release(broker);
		}
	}
	
	public void reindexCollection(User user, String name) throws Exception,
			PermissionDeniedException {
		DBBroker broker = null;
		try {
			broker = brokerPool.get(user);
			broker.reindex(name);
			LOG.debug("collection " + name + " and sub collection reindexed");
		} catch (Exception e) {
			LOG.debug(e);
			throw e;
		} finally {
			brokerPool.release(broker);
		}
	}
}
