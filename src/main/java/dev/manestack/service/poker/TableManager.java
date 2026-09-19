package dev.manestack.service.poker;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import dev.manestack.service.poker.table.GameTable;


@ApplicationScoped
public class TableManager {

    private final Map<Long, GameTable> tables = new ConcurrentHashMap<>();

    // Add a new table
    public void addTable(GameTable table) {
        if (table.getTableId() == null) {
            throw new IllegalArgumentException("Table must have an ID");
        }
        tables.put(table.getTableId(), table);
    }

    // Get table by ID
    public GameTable getTable(Long tableId) {
        return tables.get(tableId);
    }

    // Remove table
    public void removeTable(Long tableId) {
        tables.remove(tableId);
    }

    // Get all tables
    public Collection<GameTable> getAllTables() {
        return tables.values();
    }

    // Check if table exists
    public boolean exists(Long tableId) {
        return tables.containsKey(tableId);
    }

    public GameTable getTableById(long tableId) {
        return tables.get(tableId);
    }
}
