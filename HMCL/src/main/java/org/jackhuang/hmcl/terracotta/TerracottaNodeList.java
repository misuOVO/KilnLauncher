/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.terracotta;

import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.gson.JsonSerializable;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.gson.TolerableValidationException;
import org.jackhuang.hmcl.util.gson.Validation;
import org.jackhuang.hmcl.util.i18n.LocaleUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves the EasyTier relay nodes that Terracotta joins when hosting or joining a room.
///
/// The upstream node list (`https://terracotta.glavo.site/nodes`) is maintained by HMCL, but it
/// is occasionally degraded: an entry may be dropped from the list, and a listed entry may be
/// unreachable or answer with an empty body. Terracotta can only establish a room when at least
/// one relay is reachable, so an empty or broken list makes every room fail with
/// `Cannot find scaffolding server` even though both launchers and the room code itself are fine.
///
/// To keep rooms working in that situation, the effective list always starts with
/// [#FALLBACK_NODES], followed by the remote list and by the nodes supplied through the
/// `kiln.terracotta.nodes` system property (comma or whitespace separated). The remote list is
/// never dropped, and duplicates are removed while preserving the order.
///
/// @author Glavo
@NotNullByDefault
public final class TerracottaNodeList {
    /// Endpoint returning the HMCL-maintained relay list.
    private static final String NODE_LIST_URL = "https://terracotta.glavo.site/nodes";

    /// System property holding additional relay nodes, separated by commas or whitespace.
    ///
    /// This allows an operator to pin a private relay without rebuilding the launcher.
    public static final String EXTRA_NODES_PROPERTY = "kiln.terracotta.nodes";

    /// Relays that are appended when the remote list is empty or incomplete.
    ///
    /// These are volunteer-run EasyTier relays reachable from mainland China. They are only used
    /// as a supplement: a remote entry is always preferred and duplicates are removed.
    private static final List<URI> FALLBACK_NODES = List.of(
            URI.create("https://terracotta.glavo.site/acebc7d8-1208-47fd-b212-d03ac49e36e0")
    );

    /// A single entry of the remote relay list.
    private record TerracottaNode(String url, @Nullable String region) implements Validation {
        @Override
        public void validate() throws JsonParseException, TolerableValidationException {
            Validation.requireNonNull(url, "TerracottaNode.url cannot be null");
            try {
                new URI(url);
            } catch (URISyntaxException e) {
                throw new JsonParseException("Invalid URL: " + url, e);
            }
        }
    }

    /// Cached merged relay list; `null` until the first successful resolution.
    private static volatile @Nullable List<URI> list;

    /// Returns the relay nodes Terracotta should use.
    ///
    /// The remote list is fetched at most once per launcher run. Network failures are not fatal:
    /// the fallback nodes are returned instead, so hosting and joining still work offline from
    /// the node list endpoint.
    ///
    /// @return an unmodifiable, deduplicated list of relay URIs; never empty
    public static @Unmodifiable List<URI> fetch() {
        List<URI> list = TerracottaNodeList.list;
        if (list != null)
            return list;

        synchronized (TerracottaNodeList.class) {
            list = TerracottaNodeList.list;
            if (list != null)
                return list;

            list = merge(fetchRemoteNodes());
            TerracottaNodeList.list = list;
            return list;
        }
    }

    /// Fetches and filters the remote relay list.
    ///
    /// Entries are dropped when they are malformed or when they are restricted to a region other
    /// than the current one. Any failure yields an empty list so that [#FALLBACK_NODES] takes over.
    ///
    /// @return the usable remote relays, possibly empty
    private static @Unmodifiable List<URI> fetchRemoteNodes() {
        try {
            List<TerracottaNode> nodes = HttpRequest.GET(NODE_LIST_URL)
                    .getJson(JsonUtils.listTypeOf(TerracottaNode.class));

            if (nodes == null) {
                LOG.info("No available Terracotta nodes found");
                return List.of();
            }

            List<URI> remote = nodes.stream()
                    .filter(node -> {
                        if (node == null)
                            return false;

                        try {
                            node.validate();
                        } catch (Exception e) {
                            LOG.warning("Invalid terracotta node: " + node, e);
                            return false;
                        }

                        return StringUtils.isBlank(node.region) || LocaleUtils.IS_CHINA_MAINLAND == "CN".equalsIgnoreCase(node.region);
                    })
                    .map(it -> URI.create(it.url()))
                    .toList();
            LOG.info("Terracotta node list: " + remote);
            return remote;
        } catch (Exception e) {
            LOG.warning("Failed to fetch terracotta node list", e);
            return List.of();
        }
    }

    /// Builds the effective relay list from the remote entries, the fallback relays and the
    /// operator-provided relays.
    ///
    /// @param remote the relays returned by the node list endpoint, possibly empty
    /// @return an unmodifiable, deduplicated list; never empty
    private static @Unmodifiable List<URI> merge(@Unmodifiable List<URI> remote) {
        // Known-good relays go first so that EasyTier registers them before the volatile remote
        // entries, which are frequently unreachable or answer with an empty body.
        Set<URI> merged = new LinkedHashSet<>(FALLBACK_NODES);
        merged.addAll(remote);
        merged.addAll(readExtraNodes());

        // A node that appears twice would make EasyTier join the same relay twice.
        List<URI> result = List.copyOf(new ArrayList<>(merged));
        LOG.info("Effective Terracotta node list: " + result);
        return result;
    }

    /// Parses the relays supplied through [#EXTRA_NODES_PROPERTY].
    ///
    /// Only absolute URIs such as `tcp://host:port` or `https://host/path` are accepted, because
    /// EasyTier silently ignores anything else. Malformed entries are ignored with a warning
    /// instead of failing the whole room, because a typo in an optional override must never break
    /// the built-in relays.
    ///
    /// @return the parsed relays, possibly empty
    private static @Unmodifiable List<URI> readExtraNodes() {
        String value = System.getProperty(EXTRA_NODES_PROPERTY);
        if (StringUtils.isBlank(value))
            return List.of();

        List<URI> nodes = new ArrayList<>();
        for (String raw : value.split("[,\\s]+")) {
            if (StringUtils.isBlank(raw))
                continue;

            try {
                URI uri = URI.create(raw.trim());
                if (!uri.isAbsolute() || StringUtils.isBlank(uri.getScheme())) {
                    LOG.warning("Ignoring invalid Terracotta node: " + raw);
                    continue;
                }
                nodes.add(uri);
            } catch (IllegalArgumentException e) {
                LOG.warning("Ignoring invalid Terracotta node: " + raw, e);
            }
        }
        return List.copyOf(nodes);
    }

    private TerracottaNodeList() {
    }
}
