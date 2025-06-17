;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.color
  (:require
   [app.common.files.tokens :as cft]
   [app.main.data.tinycolor :as tinycolor]))

(defn color-bullet-color [token-color-value]
  (when-let [tc (tinycolor/valid-color token-color-value)]
    (if (tinycolor/alpha tc)
      {:color (tinycolor/->hex-string tc)
       :opacity (tinycolor/alpha tc)}
      (tinycolor/->hex-string tc))))

(defn resolved-token-bullet-color [{:keys [resolved-value] :as token}]
  (when (and resolved-value (cft/color-token? token))
    (color-bullet-color resolved-value)))