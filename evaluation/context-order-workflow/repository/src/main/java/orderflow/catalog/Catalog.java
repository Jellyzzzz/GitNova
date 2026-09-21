package orderflow.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Catalog {
    private final Map<String, Product> products;

    public Catalog(Collection<Product> source) {
        var copy = new LinkedHashMap<String, Product>();
        for (Product product : source) {
            if (copy.putIfAbsent(product.sku(), product) != null) {
                throw new IllegalArgumentException("duplicate SKU");
            }
        }
        products = Map.copyOf(copy);
    }

    public static Catalog load(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty() || !lines.get(0).equals("sku,unitCents,stock")) {
            throw new IllegalArgumentException("catalog header mismatch");
        }
        var products = new java.util.ArrayList<Product>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(",", -1);
            if (cells.length != 3) throw new IllegalArgumentException("invalid catalog row " + (i + 1));
            products.add(new Product(cells[0], Long.parseLong(cells[1]), Integer.parseInt(cells[2])));
        }
        return new Catalog(products);
    }

    public Product require(String sku) {
        Product product = products.get(sku);
        if (product == null) throw new IllegalArgumentException("unknown SKU: " + sku);
        return product;
    }

    public Collection<Product> all() { return products.values(); }
}
