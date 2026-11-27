;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.browser-test
  (:require
   ["node:vm" :as vm]
   [app.browser :as bw]
   [app.common.logging :as l]
   [app.renderer.bitmap :as bitmap]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(defn- harness
  [urls]
  (let [state ((js* "(urls) => {
    const state = {images: [], frames: 0};
    state.context = {
      document: {querySelectorAll: () => urls.map(src => ({src}))},
      Image: class {
        constructor() { state.images.push(this); }
        set src(value) { this.url = value; }
      },
      requestAnimationFrame: (fn) => { state.frames++; fn(); }
    };
    return state;
  }") urls)
        page  #js {:evaluate (fn [callback]
                               (js/Promise.resolve
                                (vm/runInNewContext (str "(" (.toString callback) ")()")
                                                    (.-context state))))}]
    [state page]))

(t/deftest image-probes-wait-and-flush
  (t/async done
    (let [[state page] (harness #js ["https://example.test/image" "data:image/png;base64,AA"])
          finished     (atom false)
          result       (-> (bw/wait-for-images page)
                           (p/then #(reset! finished true)))]
      (t/is (= 1 (alength (.-images state))))
      (t/is (undefined? (.-crossOrigin (aget (.-images state) 0))))
      (t/is (false? @finished))
      ((.-onload (aget (.-images state) 0)))
      (p/then result (fn []
                       (t/is (= 2 (.-frames state)))
                       (done))))))

(t/deftest empty-and-data-only-pages-flush
  (t/async done
    (let [[state page] (harness #js ["data:image/png;base64,AA"])]
      (p/then (bw/wait-for-images page)
              (fn []
                (t/is (= 0 (alength (.-images state))))
                (t/is (= 2 (.-frames state)))
                (done))))))

(t/deftest failures-are-reported-without-sensitive-details
  (t/async done
    (let [records (atom [])
          old-emit l/emit-log
          [state page] (harness #js ["https://secret.test/image"])
          stalled #js {:evaluate (fn [_] (js/Promise. (fn [_ _])))}
          broken #js {:evaluate (fn [_] (p/rejected (js/Error. "https://secret.test/token")))}]
      (set! l/emit-log (fn [props _ _ logger level _]
                         (when (and (= logger "app.browser") (= level :warn))
                           (swap! records conj @props))))
      (let [failure (bw/wait-for-images page)]
        ((.-onerror (aget (.-images state) 0)))
        (-> (p/let [_ failure
                    _ (bw/wait-for-images stalled {:timeout 0})
                    _ (bw/wait-for-images broken)
                    _ (bw/wait-for-images (second (harness #js [])))]
              (t/is (= [[[:hint "wait-for-images failed to load an image; continuing anyway"]]
                        [[:hint "wait-for-images timed out; continuing anyway"]]
                        [[:hint "wait-for-images evaluation failed; continuing anyway"]]]
                       @records))
              (t/is (= 2 (.-frames state))))
            (p/catch (fn [error] (t/is false (str error))))
            (p/finally (fn [] (set! l/emit-log old-emit) (done))))))))

(t/deftest visible-before-wait-before-each-capture
  (t/async done
    (let [events (atom [])
          old-exec bw/exec!
          scans (atom 0)
          page #js {:goto (fn [& _] (p/resolved nil))
                    :waitForTimeout (fn [& _] (p/resolved nil))
                    :waitForFunction (fn [& _] (p/resolved nil))
                    :evaluate (fn [f]
                                (if (.includes (.toString f) "querySelectorAll")
                                  (do
                                    (swap! events conj [:images])
                                    (if (= 1 (swap! scans inc))
                                      (p/resolved false)
                                      (p/rejected (js/Error. "evaluation failed"))))
                                  (p/resolved nil)))
                    :locator (fn [selector]
                               #js {:waitFor (fn [_] (swap! events conj [:visible selector]) (p/resolved nil))
                                    :screenshot (fn [_] (swap! events conj [:capture selector]) (p/resolved nil))})}]
      (set! bw/exec! (fn [_ f] (f page)))
      (-> (bitmap/render {:objects [{:id "a"} {:id "b"}] :type :png} (constantly nil))
          (p/then (fn []
                    (t/is (= [[:visible "#screenshot-a"] [:images] [:capture "#screenshot-a"]
                              [:visible "#screenshot-b"] [:images] [:capture "#screenshot-b"]]
                             @events))))
          (p/catch (fn [error] (t/is false (str error))))
          (p/finally (fn [] (set! bw/exec! old-exec) (done)))))))

(t/deftest capture-continues-after-image-timeout
  (t/async done
    (let [events (atom [])
          old-exec bw/exec!
          old-wait bw/wait-for-images
          page #js {:goto (fn [& _] (p/resolved nil))
                    :waitForTimeout (fn [& _] (p/resolved nil))
                    :waitForFunction (fn [& _] (p/resolved nil))
                    :evaluate (fn [f]
                                (if (.includes (.toString f) "querySelectorAll")
                                  (do
                                    (swap! events conj :images)
                                    (js/Promise. (fn [_ _])))
                                  (p/resolved nil)))
                    :locator (fn [_]
                               #js {:waitFor (fn [_] (swap! events conj :visible) (p/resolved nil))
                                    :screenshot (fn [_] (swap! events conj :capture) (p/resolved nil))})}]
      (set! bw/exec! (fn [_ f] (f page)))
      (set! bw/wait-for-images (fn
                                 ([page]
                                  (p/then (old-wait page {:timeout 0})
                                          (fn [_] (swap! events conj :wait-finished))))
                                 ([page _] (old-wait page {:timeout 0}))))
      (-> (bitmap/render {:objects [{:id "a"}] :type :png} (constantly nil))
          (p/then (fn []
                    (t/is (= [:visible :images :wait-finished :capture] @events))))
          (p/catch (fn [error] (t/is false (str error))))
          (p/finally (fn []
                       (set! bw/exec! old-exec)
                       (set! bw/wait-for-images old-wait)
                       (done)))))))
