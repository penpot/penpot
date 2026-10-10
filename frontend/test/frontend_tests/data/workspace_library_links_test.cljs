;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-library-links-test
  "Linking a library to the file and unlinking it, with the libraries it
   brings along, and publishing the file as a shared library. The server is
   mocked."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.variants :as cthv]
   [app.main.data.event :as ev]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(defn- rpc
  "A mock of `rp/cmd!` that answers each command from `responses`
   (command -> value, or fn of the params returning an observable), and
   records the calls in `calls`."
  [calls responses]
  (mock/stub
   (fn [cmd params & _]
     (swap! calls conj [cmd params])
     (let [response (get responses cmd)]
       (if (fn? response)
         (response params)
         (rx/of response))))))

(defn- commands
  [calls]
  (set (map first @calls)))

(defn- watch-events
  "Collect the props of the analytics events `store` receives from now on."
  [store]
  (let [events (atom [])]
    (rx/sub! (->> (ptk/input-stream store)
                  (rx/filter (ptk/type? ::ev/event))
                  (rx/map deref))
             #(swap! events conj %))
    events))

(defn- named
  [events name]
  (filterv #(= name (::ev/name %)) @events))

(defn- merge-state
  "An event that merges `m` into the state, for what the workspace loads
   before these events run."
  [m]
  (ptk/reify ::merge-state
    ptk/UpdateEvent
    (update [_ state]
      (merge-with merge state m))))

(defn- files
  []
  {:file (cthf/sample-file :file1)
   :library (-> (cthf/sample-file :library :is-shared true)
                (ctho/add-simple-component :lib-c :lib-main :lib-main-child))
   :dependency (-> (cthf/sample-file :dependency :is-shared true)
                   (ctho/add-simple-component :dep-c :dep-main :dep-main-child))})

(defn- linked-to
  [state file-id]
  (->> (vals (:files state))
       (filter #(= file-id (:library-of %)))
       (map :id)
       set))

(t/deftest link-loads-the-library-and-its-dependencies
  (t/async
    done
    (let [{:keys [file library dependency]} (files)
          by-id (into {} (map (juxt :id identity)) [library dependency])
          calls (atom [])]
      (mock/with-mocks
        {rp/cmd! (rpc calls {:link-file-to-library [(:id dependency)]
                             :get-file #(rx/of (get by-id (:id %)))
                             :get-file-object-thumbnails {"thumbnail-id" "thumbnail-uri"}
                             :get-library-usage {:used-in 3}})}
        (fn [inner-done]
          (let [store  (ths/setup-store file)
                events (watch-events store)]
            (ths/run-store
             store inner-done
             [(merge-state {:shared-files {(:id library) {:library-summary {:variants [{} {}]}}}})
              (dwl/link-file-to-library (:id file) (:id library))]
             (fn [state]
               (t/is (= #{(:id library) (:id dependency)} (linked-to state (:id file))))
               (t/is (= "thumbnail-uri" (get-in state [:thumbnails "thumbnail-id" :uri])))
               (t/testing "a library with variants reports how much it is used"
                 (t/is (contains? (commands calls) :get-library-usage))
                 (t/is (= [[3 2]]
                          (mapv (juxt :library-used-in :variants-count)
                                (named events "link-file-to-library-variants")))))))))
        done))))

(t/deftest link-skips-libraries-already-loaded
  (t/async
    done
    (let [{:keys [file library dependency]} (files)
          calls (atom [])]
      (mock/with-mocks
        {rp/cmd! (rpc calls {:link-file-to-library [(:id dependency)]
                             :get-file #(rx/of library)
                             :get-file-object-thumbnails {}})}
        (fn [inner-done]
          (ths/run-store
           (ths/setup-store file {:libraries [dependency]}) inner-done
           [(dwl/link-file-to-library (:id file) (:id library))]
           (fn [_]
             (t/is (= [(:id library)]
                      (->> @calls
                           (filter #(= :get-file (first %)))
                           (map (comp :id second)))))
             (t/is (not (contains? (commands calls) :get-library-usage))))))
        done))))

(t/deftest link-that-would-make-a-loop
  (t/async
    done
    (let [{:keys [file library]} (files)
          calls (atom [])]
      (mock/with-mocks
        {rp/cmd! (rpc calls {:link-file-to-library
                             (fn [_] (rx/throw (ex-info "loop" {:code :circular-library-reference})))})}
        (fn [inner-done]
          (ths/run-store
           (ths/setup-store file) inner-done
           [(dwl/link-file-to-library (:id file) (:id library))]
           (fn [state]
             (t/is (= :error (get-in state [:notification :level])))
             (t/is (empty? (linked-to state (:id file)))))))
        done))))

(t/deftest unlink-drops-the-library-and-what-it-brought
  (t/async
    done
    (let [{:keys [file library dependency]} (files)
          calls (atom [])]
      (mock/with-mocks
        {rp/cmd! (rpc calls {:unlink-file-from-library nil
                             :get-file-libraries []})}
        (fn [inner-done]
          ;; the file is marked as a library of itself, so only the guard
          ;; that keeps the current file keeps it
          (ths/run-store
           (ths/setup-store (assoc file :library-of (:id file))) inner-done
           [(dwl/libraries-fetched (:id file) [library dependency])
            (dwl/unlink-file-from-library (:id file) (:id library))]
           (fn [state]
             (t/is (= #{(:id file)} (linked-to state (:id file))))
             (t/is (some? (get-in state [:files (:id file)]))))))
        done))))

(t/deftest cleanup-keeps-the-libraries-still-linked
  (t/async
    done
    (let [{:keys [file library dependency]} (files)]
      (ths/run-store
       (ths/setup-store file) done
       [(dwl/libraries-fetched (:id file) [library dependency])
        (dwl/cleanup-unlinked-libraries (:id file) [library])]
       (fn [state]
         (t/is (= #{(:id library)} (linked-to state (:id file)))))))))

(defn- share
  [file shared? check]
  (fn [done]
    (let [calls (atom [])]
      (mock/with-mocks
        {rp/cmd! (rpc calls {:set-file-shared nil})}
        (fn [inner-done]
          (let [store  (ths/setup-store file)
                events (watch-events store)]
            (ths/run-store
             store inner-done
             [(dwl/set-file-shared (:id file) shared?)]
             #(check % @calls (named events "set-file-variants-shared")))))
        done))))

(t/deftest share-a-file-with-variants
  (t/async
    done
    (let [file (-> (cthf/sample-file :file1)
                   (cthv/add-variant :v01 :c01 :m01 :c02 :m02))]
      ((share file true
              (fn [state calls variant-events]
                (t/is (true? (get-in state [:files (:id file) :is-shared])))
                (t/is (= [[:set-file-shared {:id (:id file) :is-shared true}]] calls))
                (t/is (= 1 (count variant-events)) "it reports that the shared file has variants")))
       done))))

(t/deftest unshare-a-file
  (t/async
    done
    (let [file (cthf/sample-file :file1 :is-shared true)]
      ((share file false
              (fn [state calls variant-events]
                (t/is (false? (get-in state [:files (:id file) :is-shared])))
                (t/is (= [[:set-file-shared {:id (:id file) :is-shared false}]] calls))
                (t/is (empty? variant-events))))
       done))))
