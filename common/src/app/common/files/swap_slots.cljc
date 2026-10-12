;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.files.swap-slots
  "Files older than the feature components/swap-group mark a swapped copy
  head with a swap slot: a touched group `swap-slot-<id>`, where the id is
  the deepest main child of the position the head takes. Validation reports
  them, and the migrations read and repair them until
  `app.common.files.comp-processors/convert-swap-slots` turns them into
  override groups."
  (:require
   [app.common.data :as d]
   [app.common.types.component :as ctk]
   [cuerdas.core :as str]))

(defn swap-slot-group?
  [group]
  (str/starts-with? (name group) "swap-slot-"))

(defn build-swap-slot-group
  "Convert a swap slot into a :touched group."
  [swap-slot]
  (when swap-slot
    (keyword (str "swap-slot-" swap-slot))))

(defn normal-touched-groups
  "The touched groups of the shape that are not swap slots."
  [shape]
  (into #{} (remove swap-slot-group?) (:touched shape)))

(defn get-swap-slot
  "If the shape has a :touched group in the form :swap-slot-<uuid>, get the id."
  [shape]
  (when-let [group (d/seek swap-slot-group? (:touched shape))]
    (parse-uuid (subs (name group) 10))))  ;; 10 is the length of "swap-slot-"

(defn set-swap-slot
  "Add a touched group with a form :swap-slot-<uuid>."
  [shape swap-slot]
  (cond-> shape
    (some? swap-slot)
    (update :touched ctk/set-touched-group (build-swap-slot-group swap-slot))))

(defn remove-swap-slot
  [shape]
  (update shape :touched #(into #{} (remove swap-slot-group?) %)))
