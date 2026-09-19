package dev.manestack.config;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

public class Configurator {

    @Inject
    AgroalDataSource ds;

    @ApplicationScoped
    public DSLContext getContext() {
        return DSL.using(ds, SQLDialect.POSTGRES);
    }
}
