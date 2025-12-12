;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.common.typography
  (:require
   [app.main.refs :as refs]
   [app.main.store :as st]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- make-typographies-library-ref
  [file-id]
  (let [get-library
        (fn [state]
          (get-in state [:viewer-libraries file-id :data :typographies]))]
    #(l/derived get-library st/state)))

(def ^:private file-typographies-ref
  (l/derived (l/in [:viewer :file :data :typographies]) st/state))

(defn get-typography
  [style]
  (let [typography-library-ref
        (mf/use-memo
         (mf/deps (:typography-ref-file style))
         (make-typographies-library-ref (:typography-ref-file style)))

        ; FIXME: too many duplicate operations
        typography-library (mf/deref typography-library-ref)
        file-typographies-viewer (mf/deref file-typographies-ref)
        file-typographies-workspace (mf/deref refs/workspace-file-typography)
        file-library-workspace   (get (mf/deref refs/files) (:typography-ref-file style))
        typography-external-lib (get-in file-library-workspace [:data :typographies (:typography-ref-id style)])
        typography (or (get (or typography-library file-typographies-viewer file-typographies-workspace) (:typography-ref-id style)) typography-external-lib)]
    typography))
