package com.funnywork;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ToolContractTest {
    @Test void stringSchemaAndValidatorCountUnicodeCodepoints() {
        var contract=new ToolContract("fixture.text",ToolContract.Effect.READ_ONLY,Map.of("value",new ToolContract.Field(ToolContract.Kind.STRING,true,1)),"vault://fixture/key","fixture.verify");
        assertEquals("😀",contract.validate(Map.of("value","😀")).get("value"));
        assertThrows(ApiException.class,()->contract.validate(Map.of("value","😀x")));
    }
    @Test void integerSchemaAndCanonicalValidationAgree() {
        var contract=new ToolContract("fixture.integer",ToolContract.Effect.READ_ONLY,Map.of("value",new ToolContract.Field(ToolContract.Kind.INTEGER,true,1)),"vault://fixture/key","fixture.verify");
        assertEquals(1L,contract.validate(Map.of("value",1.0)).get("value"));
        assertThrows(ApiException.class,()->contract.validate(Map.of("value",1.5)));
        assertThrows(ApiException.class,()->contract.validate(Map.of("value",Double.NaN)));
        assertThrows(ApiException.class,()->contract.validate(Map.of("value",new java.math.BigInteger("9223372036854775808"))));
        Map<?,?> field=(Map<?,?>)((Map<?,?>)contract.inputSchema().get("properties")).get("value");
        assertEquals(Long.MAX_VALUE,field.get("maximum"));
    }
}
