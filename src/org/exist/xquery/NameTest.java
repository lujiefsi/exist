package org.exist.xquery;

import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.QName;
import org.exist.dom.memtree.NodeImpl;
import org.exist.dom.memtree.ReferenceNode;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.Type;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamReader;

public class NameTest extends TypeTest {

	protected final QName nodeName;

	public NameTest(int type, QName name) throws XPathException {
		super(type);
        name.isValid(true);

		nodeName = name;
	}

	public QName getName() {
		return nodeName;
	}

	/* (non-Javadoc)
	 * @see org.exist.xquery.NodeTest#matches(org.exist.dom.persistent.NodeProxy)
	 */
	public boolean matches(NodeProxy proxy) {
		Node node = null;
		short type = proxy.getNodeType();
		if(proxy.getType() == Type.ITEM) {
			node = proxy.getNode();
			type = node.getNodeType();
		}
		if (!isOfType(type))
			{return false;}
		if(node == null)
			{node = proxy.getNode();}
        return matchesName(node);
	}

	public boolean matches(Node other) {
        if (other.getNodeType() == NodeImpl.REFERENCE_NODE) {
            return matches(((ReferenceNode)other).getReference());
        }

        if(!isOfType(other.getNodeType())) {
            return false;
        }

        return matchesName(other);
	}

	@Override
	public boolean matches(final QName name) {
		return nodeName.matches(name);
	}
	
    public boolean matchesName(final Node other) {
        if (other.getNodeType() == NodeImpl.REFERENCE_NODE) {
            return matchesName(((ReferenceNode)other).getReference().getNode());
        }

        if(nodeName == QName.WildcardQName.getInstance()) {
            return true;
        }

        if(!(nodeName instanceof QName.WildcardNamespaceURIQName)) {
            String otherNs = other.getNamespaceURI();
            if(otherNs == null) {
                otherNs = XMLConstants.NULL_NS_URI;
            }
            if (!nodeName.getNamespaceURI().equals(otherNs)) {
                return false;
            }
		}

        if(!(nodeName instanceof QName.WildcardLocalPartQName)) {
			return nodeName.getLocalPart().equals(other.getLocalName());
		}

		return true;
	}

	@Override
    public boolean matches(final XMLStreamReader reader) {
        final int ev = reader.getEventType();
        if (!isOfEventType(ev)) {
            return false;
        }

        if(nodeName == QName.WildcardQName.getInstance()) {
            return true;
        }

        switch (ev) {
            case XMLStreamReader.START_ELEMENT :
                if(!(nodeName instanceof QName.WildcardNamespaceURIQName)) {
                    String readerNs = reader.getNamespaceURI();
                    if(readerNs == null) {
                        readerNs = XMLConstants.NULL_NS_URI;
                    }
                    if (!nodeName.getNamespaceURI().equals(readerNs)) {
                        return false;
                    }
                }

                if(!(nodeName instanceof QName.WildcardLocalPartQName)) {
                    return nodeName.getLocalPart().equals(reader.getLocalName());
                }
                break;

            case XMLStreamReader.PROCESSING_INSTRUCTION :
                if(!(nodeName instanceof QName.WildcardLocalPartQName)) {
                    return nodeName.getLocalPart().equals(reader.getPITarget());
                }
                break;
        }
        return true;
    }

    /* (non-Javadoc)
	 * @see org.exist.xquery.NodeTest#isWildcardTest()
	 */
	public boolean isWildcardTest() {
        return nodeName instanceof QName.PartialQName;
	}

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NameTest) {
            final NameTest other = (NameTest) obj;
            return other.nodeType == nodeType && other.nodeName.equals(nodeName);
        }
        return false;
    }

    public void dump(ExpressionDumper dumper) {
        dumper.display(nodeName.getStringValue());
    }    

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
    public String toString() {
        final StringBuilder result = new StringBuilder();
        
        if(nodeName.getPrefix() != null) {
            result.append(nodeName.getPrefix());
            result.append(":");
        } else if(!(nodeName instanceof QName.WildcardNamespaceURIQName)) {
            result.append("{");
            result.append(nodeName.getNamespaceURI());
            result.append("}");
        }

        result.append(nodeName.getLocalPart());
        
        return result.toString();
    }

}
