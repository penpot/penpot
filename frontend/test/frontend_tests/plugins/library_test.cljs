;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.library-test
  (:require
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.variants :as dwv]
   [app.main.store :as st]
   [app.plugins.library :as library]
   [app.plugins.register :as r]
   [app.plugins.text :as text]
   [app.plugins.utils :as u]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(t/deftest library-asset-proxies-expose-library-id
  (let [file-id (random-uuid)
        id      (random-uuid)]
    (t/is (= (str file-id) (.-libraryId (library/lib-color-proxy plugin-id file-id id))))
    (t/is (= (str file-id) (.-libraryId (library/lib-typography-proxy plugin-id file-id id))))
    (t/is (= (str file-id) (.-libraryId (library/lib-component-proxy plugin-id file-id id))))))

(t/deftest typography-apply-to-text-range-uses-hidden-range-bounds
  (let [file-id       (random-uuid)
        page-id       (random-uuid)
        shape-id      (random-uuid)
        typography-id (random-uuid)
        typography    (library/lib-typography-proxy plugin-id file-id typography-id)
        text-range    (text/text-range-proxy plugin-id file-id page-id shape-id 2 5)
        captured      (atom nil)]
    (with-redefs [r/check-permission (constantly true)
                  u/page-active? (constantly true)
                  u/locate-library-typography
                  (constantly {:id typography-id
                               :name "Body"
                               :font-size "14"})
                  dwt/update-text-range
                  (fn [shape-id start end attrs]
                    (reset! captured {:shape-id shape-id
                                      :start start
                                      :end end
                                      :attrs attrs})
                    :update-text-range)
                  st/emit! mock/noop]
      (.applyToTextRange typography text-range)
      (t/is (= shape-id (:shape-id @captured)))
      (t/is (= 2 (:start @captured)))
      (t/is (= 5 (:end @captured)))
      (t/is (= file-id (get-in @captured [:attrs :typography-ref-file])))
      (t/is (= typography-id (get-in @captured [:attrs :typography-ref-id]))))))

(t/deftest library-color-gradient-and-image-clear-exclusive-representations
  (let [file-id  (random-uuid)
        color-id (random-uuid)
        proxy    (library/lib-color-proxy plugin-id file-id color-id)
        captured (atom nil)
        base     {:id color-id
                  :name "Brand"
                  :color "#fabada"
                  :opacity 1
                  :gradient {:type :linear}
                  :image {:id (random-uuid) :width 1 :height 1}}]
    (with-redefs [r/check-permission (constantly true)
                  u/proxy->library-color (constantly base)
                  dwl/update-color-data (fn [color file-id]
                                          (reset! captured {:color color :file-id file-id})
                                          :update-color-data)
                  st/emit! mock/noop]
      (set! (.-gradient proxy)
            #js {:type "linear"
                 :startX 0
                 :startY 0
                 :endX 1
                 :endY 1
                 :width 1
                 :stops #js [#js {:color "#000000"
                                  :opacity 1
                                  :offset 0}]})
      (t/is (contains? (:color @captured) :gradient))
      (t/is (not (contains? (:color @captured) :color)))
      (t/is (not (contains? (:color @captured) :image)))

      (set! (.-image proxy)
            #js {:id (str (random-uuid))
                 :width 10
                 :height 20
                 :mtype "image/png"})
      (t/is (contains? (:color @captured) :image))
      (t/is (not (contains? (:color @captured) :color)))
      (t/is (not (contains? (:color @captured) :gradient))))))

;; ---------------------------------------------------------------------------
;; Permission checks (T9-F-02)
;; ---------------------------------------------------------------------------

(t/deftest variant-add-variant-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        id        (uuid/next)
        errors    (atom [])]
    (with-redefs [r/check-permission (constantly false)
                  u/not-valid        (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  st/emit!           mock/noop]
      (let [proxy (library/variant-proxy plugin-id file-id id)]
        (.addVariant proxy)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :addVariant "Plugin doesn't have 'library:write' permission"]
                 (first @errors)))))))

(t/deftest variant-add-property-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        id        (uuid/next)
        errors    (atom [])]
    (with-redefs [r/check-permission (constantly false)
                  u/not-valid        (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  st/emit!           mock/noop]
      (let [proxy (library/variant-proxy plugin-id file-id id)]
        (.addProperty proxy)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :addProperty "Plugin doesn't have 'library:write' permission"]
                 (first @errors)))))))

(t/deftest variant-remove-property-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        id        (uuid/next)
        errors    (atom [])]
    (with-redefs [r/check-permission     (constantly false)
                  u/not-valid            (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  library/get-variant-components (constantly [{:variant-properties [{:name "color" :value "red"}]}])
                  st/emit!               mock/noop]
      (let [proxy (library/variant-proxy plugin-id file-id id)]
        (.removeProperty proxy 0)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :removeProperty "Plugin doesn't have 'library:write' permission"]
                 (first @errors)))))))

(t/deftest variant-rename-property-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        id        (uuid/next)
        errors    (atom [])]
    (with-redefs [r/check-permission     (constantly false)
                  u/not-valid            (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  library/get-variant-components (constantly [{:variant-properties [{:name "color" :value "red"}]}])
                  st/emit!               mock/noop]
      (let [proxy (library/variant-proxy plugin-id file-id id)]
        (.renameProperty proxy 0 "newName")
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :renameProperty "Plugin doesn't have 'library:write' permission"]
                 (first @errors)))))))

(t/deftest component-transform-in-variant-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        id        (uuid/next)
        errors    (atom [])]
    (with-redefs [r/check-permission    (constantly false)
                  u/not-valid           (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  u/locate-library-component (constantly {:id id :main-instance-id id})
                  ctk/is-variant?       (constantly false)
                  st/emit!              mock/noop]
      (let [proxy (library/lib-component-proxy plugin-id file-id id)]
        (.transformInVariant proxy)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :transformInVariant "Plugin doesn't have 'library:write' permission"]
                 (first @errors)))))))

(t/deftest component-add-variant-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        id        (uuid/next)
        errors    (atom [])]
    (with-redefs [r/check-permission    (constantly false)
                  u/not-valid           (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  u/locate-library-component (constantly {:id id :main-instance-id id})
                  ctk/is-variant?       (constantly true)
                  st/emit!              mock/noop]
      (let [proxy (library/lib-component-proxy plugin-id file-id id)]
        (.addVariant proxy)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :addVariant "Plugin doesn't have 'library:write' permission"]
                 (first @errors)))))))

(t/deftest component-set-variant-property-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        id        (uuid/next)
        errors    (atom [])]
    (with-redefs [r/check-permission    (constantly false)
                  u/not-valid           (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  u/locate-library-component (constantly {:id id :variant-properties [{:name "color"}]})
                  st/emit!              mock/noop]
      (let [proxy (library/lib-component-proxy plugin-id file-id id)]
        (.setVariantProperty proxy 0 "red")
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :setVariantProperty "Plugin doesn't have 'library:write' permission"]
                 (first @errors)))))))
