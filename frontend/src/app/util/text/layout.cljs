;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.layout
  (:require
   [app.util.text.writing-mode :as wm]
   [clojure.string :as str]))

(defn controls-enabled?
  [{:keys [feature-enabled file? all-files?]}]
  (and (true? feature-enabled)
       ^boolean (wm/vertical-layout-active?)
       (boolean (or file? all-files?))))

(defn file-action-visible?
  [{:keys [feature-enabled all-files?]} can-edit? read-only?]
  (and (true? feature-enabled)
       ^boolean (wm/vertical-layout-active?)
       (true? can-edit?)
       (not read-only?)
       (not all-files?)))

(defn profile-matches?
  [language text-language]
  (and (string? text-language)
       (= (case language :japanese "ja" :chinese "zh" :korean "ko")
          (first (str/split (str/lower-case text-language) #"-")))))
