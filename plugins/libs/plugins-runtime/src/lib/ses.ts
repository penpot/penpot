let isLockedDown = false;

export const ses = {
  hardenIntrinsics: () => {
    if (!isLockedDown) {
      isLockedDown = true;
      hardenIntrinsics();
    }
  },
  createCompartment: (globals?: object) => {
    return new Compartment(globals);
  },
  harden: (obj: object) => {
    return harden(obj);
  },
  safeReturn<T>(value: T): T {
    if (value === null || value === undefined) {
      return value;
    }

    return harden(value) as T;
  },
};
