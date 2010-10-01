/*
 * Copyright (c) 2005-2010 Grameen Foundation USA
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * See also http://www.apache.org/licenses/LICENSE-2.0.html for an
 * explanation of the license and how it is applied.
 */

package org.example;

import java.io.InputStream;

import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;
import org.mifos.spi.TransactionImport;
import org.mifos.spi.ParseResultDto;

public class GroovyPluginRunner extends TransactionImport {
    @Override
    public String getDisplayName() {
        return "Example Groovy Mifos Plugin";
    }

    @Override
    public ParseResultDto parse(final InputStream input) {
        String mifosGroovyPluginDir = System.getProperty("user.home") + "/.mifos/groovy";
        String[] roots = new String[] { mifosGroovyPluginDir };
        Binding binding = new Binding();
        try {
            GroovyScriptEngine gse = new GroovyScriptEngine(roots);
            binding.setVariable("input", input);
            binding.setVariable("parent", this);
            gse.run("examplePlugin.groovy", binding);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return (ParseResultDto) binding.getVariable("parseResultDto");
    }

    @Override
    public void store(InputStream input) throws Exception {
        getAccountService().makePayments(parse(input).getSuccessfullyParsedPayments());
    }
}
