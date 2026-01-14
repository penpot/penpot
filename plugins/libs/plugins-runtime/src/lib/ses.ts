let isLockedDown = false;

export const ses = {
  hardenIntrinsics: () => {
    if (!isLockedDown) {
      isLockedDown = true;
      hardenIntrinsics();
    }
  },
  createCompartment: (globals?: Object) => {
    return new Compartment(globals);
  },
  harden: (obj: Object) => {
    return harden(obj);
  },
  safeReturn<T>(value: T): T {
    if (value === null || value === undefined) {
      return value;
    }

    return harden(value) as T;
  },
};
