/*
 * Copyright (C) 2026 Jason von Nieda <jason@vonnieda.org>, Tony Luken <tonyluken62+openpnp@gmail.com>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Verifies that there are no file or directory paths in the OpenPnP project that differ
 * only by case. This helps prevents problems for developers working on Windows machines as 
 * Windows doesn't differentiate files based on the case of the path/file name. This can lead to
 * Windows silently merging the contents of different directories together as well as files
 * being overwritten during GitHub fetches.
 * 
 * Note, strictly for performance reasons, files and directories specified in the top level 
 * .gitignore file are exempted from this check.
 */
public class CaseSensitiveDuplicateFileTest {

    @Test
    public void testNoCaseCollisions() throws IOException {
        //Create a list of file/directories to ignore based on the top level .gitignore file
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path gitIgnorePath = projectRoot.resolve(".gitignore");
        
        ArrayList<String> ignoreList = new ArrayList<>();
        
        try (Stream<String> lines = Files.lines(gitIgnorePath)) {
            ignoreList = lines.filter(line -> !line.trim().isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        ignoreList.add(".git"); //always ignore everything in the hidden .git directory

        //Create a list of PathMatchers that will match the same files as those specified in the
        //.gitignore file. Note that Java's glob matching patterns are slightly different that that
        //used by .gitignore so that is accounted for here.
        ArrayList<PathMatcher> ignorePathMatchers = new ArrayList<>();
        for (String item : ignoreList) {
            if (item.startsWith("/")) {
                ignorePathMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + item.substring(1)));
                if (item.endsWith("/")) {
                    ignorePathMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + item.substring(1) + "/**"));
                }
            }
            else if (item.endsWith("/")) {
                ignorePathMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + "**/" + item));
                ignorePathMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + "**/" + item + "**"));
            }
            else {
                ignorePathMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + "**/" + item));
            }
        }
        
        // Map of lower case paths to a list of original case paths that share that lower case name
        Map<String, List<String>> pathGroupings = new HashMap<>();

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isIgnored(dir)) {
                    System.out.println("Skipping directory: " + dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                trackPath(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isIgnored(file)) {
                    System.out.println("Skipping file: " + file);
                }
                else {
                    trackPath(file);
                }
                return FileVisitResult.CONTINUE;
            }

            private void trackPath(Path absolutePath) {
                System.out.println("Checking: " + absolutePath);
                String lowercasePath = absolutePath.toString().toLowerCase();
                pathGroupings.computeIfAbsent(lowercasePath, k -> new ArrayList<>())
                    .add(absolutePath.toString());
            }

            /**
             * Checks to see if the specified path should be ignored based on the contents of the 
             * top level .gitignore file
             * @param path the path to check
             * @return true if the path should be ignored
             */
            private boolean isIgnored(Path path) {
                for (int i=0; i<ignorePathMatchers.size(); i++) {
                    if (ignorePathMatchers.get(i).matches(path)) {
                        return true;
                    }
                }
                return false;
            }
        });

        // Filter and collect collisions
        Map<String, List<String>> collisions = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : pathGroupings.entrySet()) {
            if (entry.getValue().size() > 1) {
                collisions.put(entry.getKey(), entry.getValue());
            }
        }

        // Fail the build if any case-insensitive collisions are found
        Assertions.assertTrue(collisions.isEmpty(), 
            "Found file/directory name collisions that differ only by case:\n" + formatCollisions(collisions));
    }

    private String formatCollisions(Map<String, List<String>> collisions) {
        StringBuilder sb = new StringBuilder();
        collisions.forEach((lower, originals) -> {
            sb.append("  - ").append(originals).append("\n");
        });
        return sb.toString();
    }}
