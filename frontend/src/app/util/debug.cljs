;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.debug)

(defonce state (atom #{#_:events}))

(def options
  #{;; Displays the bounding box for the shapes
    :bounding-boxes

    ;; Displays an overlay over the groups
    :group

    ;; Displays in the console log the events through the application
    :events

    ;; Display the boxes that represent the rotation and resize handlers
    :handlers

    ;; Displays the center of a selection
    :selection-center

    ;; When active the single selection will not take into account previous transformations
    ;; this is useful to debug transforms
    :simple-selection

    ;; When active the thumbnails will be displayed with a sepia filter
    :thumbnails

    ;; When active we can check in the browser the export values
    :show-export-metadata

    ;; Show text fragments outlines
    :text-outline

    ;; Disable thumbnail cache
    :disable-thumbnail-cache

    ;; Disable frame thumbnails
    :disable-frame-thumbnails

    ;; Force thumbnails always (independent of selection or zoom level)
    :force-frame-thumbnails

    ;; Enable a widget to show the auto-layout drop-zones
    :layout-drop-zones

    ;; Display the layout lines
    :layout-lines

    ;; Display the bounds for the hug content adjust
    :layout-content-bounds

    ;; Makes the pixel grid red so its more visibile
    :pixel-grid

    ;; Show the bounds relative to the parent
    :parent-bounds

    ;; Show html text
    :html-text

    ;; Show history overlay
    :history-overlay

    ;; Show shape name and id
    :shape-titles

    ;; Show an asterisk for touched copies
    :show-touched

    ;; Show the id with the name
    :show-ids

    ;;
    :grid-layout

    ;; Show an overlay to the grid cells to know its properties
    :grid-cells

    ;; Show info about shapes
    :shape-panel

    ;; Show what is touched in copies
    :display-touched

    ;; Show some visual indicators for bool shape
    :bool-shapes

    ;; Show some information about the WebGL context.
    :gl-context

    ;; Show viewbox
    :wasm-viewbox})

(defn enable!
  [option]
  (swap! state conj option))

(defn disable!
  [option]
  (swap! state disj option))

(defn enabled?
  ^boolean
  [option]
  (contains? @state option))

(defn toggle!
  [option]
  (if (enabled? option)
    (disable! option)
    (enable! option)))
