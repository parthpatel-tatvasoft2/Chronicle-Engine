package net.openhft.chronicle.engine2.api;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.engine2.api.collection.ValuesCollection;
import net.openhft.chronicle.engine2.api.map.MapView;
import net.openhft.chronicle.engine2.api.set.EntrySet;
import net.openhft.chronicle.engine2.api.set.KeySet;
import net.openhft.chronicle.wire.*;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Created by peter on 24/05/15.
 */
public class RequestContext<I extends Assetted> {
    private String pathName;
    private String name;
    private Asset parent;
    private Class assetType, type, type2;
    private I item;
    private String basePath;
    private Function<Bytes, Wire> wireType = TextWire::new;
    private boolean putReturnsNull, removeReturnsNull, bootstrap = true;

    private RequestContext(Asset parent) {
        this.parent = parent;
    }

    public RequestContext(String pathName, String name) {
        this.pathName = pathName;
        this.name = name;
    }

    public static RequestContext<Assetted> requestContext(Asset parent) {
        return new RequestContext<>(parent);
    }

    public static RequestContext<Assetted> requestContext(String uri) {
        int queryPos = uri.indexOf('?');
        String fullName = queryPos >= 0 ? uri.substring(0, queryPos) : uri;
        String query = queryPos >= 0 ? uri.substring(queryPos + 1) : "";
        int dirPos = fullName.lastIndexOf('/');
        String pathName = dirPos >= 0 ? fullName.substring(0, dirPos) : "";
        String name = dirPos >= 0 ? fullName.substring(dirPos + 1) : fullName;
        return new RequestContext<>(pathName, name).queryString(query);
    }

    public RequestContext<I> queryString(String queryString) {
        if (queryString.isEmpty())
            return this;
        WireParser parser = new VanillaWireParser();
        parser.register(() -> "view", v -> v.text((Consumer<String>) this::view));
        parser.register(() -> "bootstrap", v -> v.bool(b -> this.bootstrap = b));
        parser.register(() -> "putReturnsNull", v -> v.bool(b -> this.putReturnsNull = b));
        parser.register(() -> "removeReturnsNull", v -> v.bool(b -> this.removeReturnsNull = b));
        parser.register(() -> "basePath", v -> v.text((Consumer<String>) x -> this.basePath = x));
        parser.register(() -> "assetType", v -> v.typeLiteral(this::lookupType, x -> this.assetType = x));
        parser.register(() -> "keyType", v -> v.typeLiteral(this::lookupType, x -> this.type = x));
        parser.register(() -> "valueType", v -> v.typeLiteral(this::lookupType, x -> this.type2 = x));
        parser.register(() -> "elementType", v -> v.typeLiteral(this::lookupType, x -> this.type = x));
        parser.register(WireParser.DEFAULT, ValueIn.DISCARD);
        Bytes bytes = Bytes.from(queryString);
        QueryWire wire = new QueryWire(bytes);
        while (bytes.remaining() > 0)
            parser.parse(wire);
        return this;
    }

    void view(String viewName) {
        switch (viewName) {
            case "Map":
            case "map":
                assetType = MapView.class;
                break;
            case "EntrySet":
            case "entrySet":
                assetType = EntrySet.class;
                break;
            case "KeySet":
            case "keySet":
                assetType = KeySet.class;
                break;
            case "Values":
            case "values":
                assetType = ValuesCollection.class;
                break;
            case "Set":
            case "set":
                assetType = Set.class;
                break;
            default:
                throw new IllegalArgumentException("Unknown view name:" + viewName);
        }
    }

    Class lookupType(CharSequence typeName) {
        try {
            return Class.forName(typeName.toString());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    public RequestContext<I> type(Class type) {
        this.type = type;
        return this;
    }

    public Class type() {
        return type;
    }

    public RequestContext type2(Class type2) {
        this.type2 = type2;
        return this;
    }

    public Class type2() {
        return type2;
    }

    public Asset parent() {
        return parent;
    }

    public String fullName() {
        return pathName.isEmpty() ? name : (pathName + "/" + name);
    }

    public I item() {
        return item == null ? parent == null ? null : (I) parent.item() : item;
    }

    public <Item extends Assetted> RequestContext<Item> item(Item resource) {
        this.item = (I) resource;
        return (RequestContext<Item>) this;
    }

    public RequestContext<I> basePath(String basePath) {
        this.basePath = basePath;
        return this;
    }

    public String basePath() {
        return basePath;
    }

    public RequestContext<I> wireType(Function<Bytes, Wire> writeType) {
        this.wireType = writeType;
        return this;
    }

    public Function<Bytes, Wire> wireType() {
        return wireType;
    }

    public String namePath() {
        return pathName;
    }

    public String name() {
        return name;
    }

    public RequestContext<I> name(String name) {
        this.name = name;
        return this;
    }

    public <A> RequestContext<I> assetType(Class<A> assetType) {
        this.assetType = assetType;
        return this;
    }

    public Class assetType() {
        return assetType;
    }

    public boolean putReturnsNull() {
        return putReturnsNull;
    }

    public boolean removeReturnsNull() {
        return removeReturnsNull;
    }

    public RequestContext<I> fullName(String fullName) {
        int dirPos = fullName.lastIndexOf('/');
        this.pathName = dirPos >= 0 ? fullName.substring(0, dirPos) : "";
        this.name = dirPos >= 0 ? fullName.substring(dirPos + 1) : fullName;
        return this;
    }

    public boolean bootstrap() {
        return bootstrap;
    }

    @Override
    public String toString() {
        return "RequestContext{" +
                "pathName='" + pathName + '\'' +
                ", name='" + name + '\'' +
                ", parent=" + parent +
                ", assetType=" + assetType +
                ", type=" + type +
                ", type2=" + type2 +
                ", item=" + item +
                ", basePath='" + basePath + '\'' +
                ", wireType=" + wireType +
                ", putReturnsNull=" + putReturnsNull +
                ", removeReturnsNull=" + removeReturnsNull +
                ", bootstrap=" + bootstrap +
                '}';
    }
}
