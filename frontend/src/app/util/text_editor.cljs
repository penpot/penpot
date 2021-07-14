;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.text-editor
  "Draft related abstraction functions."
  (:require
   ["./text_editor_impl.js" :as impl]
   ["draft-js" :as draft]
   [app.common.text :as txt]))

;; --- CONVERSION

(defn immutable-map->map
  [obj]
  (into {} (map (fn [[k v]] [(keyword k) v])) (seq obj)))

;; --- DRAFT-JS HELPERS

(defn create-editor-state
  ([]
   (impl/createEditorState nil nil))
  ([content]
   (impl/createEditorState content nil))
  ([content decorator]
   (impl/createEditorState content decorator)))

(defn create-decorator
  [type component]
  (impl/createDecorator type component))

(defn import-content
  [content]
  (-> content txt/convert-to-draft clj->js draft/convertFromRaw))

(defn export-content
  [content]
  (-> content
      (draft/convertToRaw)
      (js->clj :keywordize-keys true)
      (txt/convert-from-draft)))

(defn get-editor-current-content
  [state]
  (.getCurrentContent ^js state))

(defn ^boolean content-has-text?
  [content]
  (.hasText ^js content))

(defn editor-select-all
  [state]
  (impl/selectAll state))

(defn get-editor-block-data
  [block]
  (-> (.getData ^js block)
      (immutable-map->map)))

(defn get-editor-block-type
  [block]
  (.getType ^js block))

(defn get-editor-current-block-data
  [state]
  (let [block (impl/getCurrentBlock state)]
    (get-editor-block-data block)))

(defn get-editor-current-inline-styles
  [state]
  (-> (.getCurrentInlineStyle ^js state)
      (txt/styles-to-attrs)))

(defn update-editor-current-block-data
  [state attrs]
  (impl/updateCurrentBlockData state (clj->js attrs)))

(defn update-editor-current-inline-styles
  [state attrs]
  (impl/applyInlineStyle state (txt/attrs-to-styles attrs)))

(defn editor-split-block
  [state]
  (impl/splitBlockPreservingData state))

(defn add-editor-blur-selection
  [state]
  (impl/addBlurSelectionEntity state))

(defn remove-editor-blur-selection
  [state]
  (impl/removeBlurSelectionEntity state))
