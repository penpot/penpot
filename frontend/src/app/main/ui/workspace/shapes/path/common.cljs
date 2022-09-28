;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.path.common
  (:require
   [app.main.refs :as refs]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def primary-color "var(--color-select)")
(def secondary-color "var(--color-distance)")
(def black-color "var(--color-black)")
(def white-color "var(--color-white)")
(def gray-color "var(--color-gray-20)")

(def current-edit-path-ref
  (let [selfn (fn [local]
                (let [id (:edition local)]
                  (get-in local [:edit-path id])))]
    (l/derived selfn refs/workspace-local)))

(defn make-edit-path-ref [id]
  (mf/use-memo
   (mf/deps id)
   (let [selfn #(get-in % [:edit-path id])]
     #(l/derived selfn refs/workspace-local))))

(defn make-content-modifiers-ref [id]
  (mf/use-memo
   (mf/deps id)
   (let [selfn #(get-in % [:edit-path id :content-modifiers])]
     #(l/derived selfn refs/workspace-local))))

