/*
  Copyright (C) 2015-2021 AsamK and contributors

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.asamk.signal;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.commands.exceptions.IOErrorException;
import org.asamk.signal.commands.exceptions.UnexpectedErrorException;
import org.asamk.signal.commands.exceptions.UntrustedKeyErrorException;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.util.SecurityProvider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.security.Security;

public class Main {

    public static void main(String[] args) {
        // enable unlimited strength crypto via Policy, supported on relevant JREs
        Security.setProperty("crypto.policy", "unlimited");
        installSecurityProviderWorkaround();

        // Configuring the logger needs to happen before any logger is initialized
        final var verboseLevel = getVerboseLevel(args);
        configureLogging(verboseLevel);

        var parser = App.buildArgumentParser();

        var ns = parser.parseArgsOrFail(args);

        int status = 0;
        try {
            new App(ns).init();
        } catch (CommandException e) {
            System.err.println(e.getMessage());
            if (verboseLevel > 0 && e.getCause() != null) {
                e.getCause().printStackTrace();
            }
            status = getStatusForError(e);
        } catch (Throwable e) {
            e.printStackTrace();
            status = 2;
        }
        System.exit(status);
    }

    private static void installSecurityProviderWorkaround() {
        // Register our own security provider
        Security.insertProviderAt(new SecurityProvider(), 1);
        Security.addProvider(new BouncyCastleProvider());
    }

    private static int getVerboseLevel(String[] args) {
        var parser = ArgumentParsers.newFor("signal-cli").build().defaultHelp(false);
        parser.addArgument("--verbose").action(Arguments.count());

        Namespace ns;
        try {
            ns = parser.parseKnownArgs(args, null);
        } catch (ArgumentParserException e) {
            return 0;
        }

        return ns.getInt("verbose");
    }

    private static void configureLogging(final int verboseLevel) {
        final var defaultLogLevel = verboseLevel > 1 ? "trace" : verboseLevel > 0 ? "debug" : "info";
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", defaultLogLevel);
        if (verboseLevel > 0) {
            System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
            System.setProperty("org.slf4j.simpleLogger.showShortLogName", "false");
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
            System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSXX");
            java.util.logging.Logger.getLogger("")
                    .setLevel(verboseLevel > 2 ? java.util.logging.Level.FINEST : java.util.logging.Level.INFO);
            Manager.initLogger();
        } else {
            System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
            System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "false");
        }
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    private static int getStatusForError(final CommandException e) {
        if (e instanceof UserErrorException) {
            return 1;
        } else if (e instanceof UnexpectedErrorException) {
            return 2;
        } else if (e instanceof IOErrorException) {
            return 3;
        } else if (e instanceof UntrustedKeyErrorException) {
            return 4;
        } else {
            return 2;
        }
    }
}
