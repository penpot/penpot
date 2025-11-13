;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.common.colors
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.color :as cc]
   [app.main.store :as st]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def file-colors-ref
  (l/derived (l/in [:viewer :file :data :colors]) st/state))

(defn make-colors-library-ref
  [libraries-place file-id]
  (let [get-library
        (fn [state]
          (get-in state [libraries-place file-id :data :colors]))]
    (l/derived get-library st/state)))

(defn use-colors-library
  [{:keys [ref-file] :as color}]
  (let [library (mf/with-memo [ref-file]
                  (make-colors-library-ref :files ref-file))]
    (mf/deref library)))

(defn color->color-space->css-format
  [color opacity color-space]
  (case color-space
    "hex"  color
    "rgba" (let [[r g b a] (cc/hex->rgba color opacity)]
             (dm/str "rgba(" (cc/format-rgba [r g b a]) ")"))
    "hsla" (let [[h s l a] (cc/hex->hsla color opacity)]
             (dm/str "hsla(" (cc/format-hsla [h s l a]) ")"))
    (:color color)))
