package org.patryk3211.powergrid.electricity.sim.special;

import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;

public class RelaySwitchWire extends SwitchedWire implements IOuterHook {
    private final AbstractElectricWire coilWire;
    private final float onCurrent;
    private final float offCurrent;
    private final boolean normallyClosed;
    private final boolean polarized;

    private boolean switched = false;

    public RelaySwitchWire(float resistance, IElectricNode node1, IElectricNode node2, AbstractElectricWire coilWire, float onCurrent, float offCurrent, boolean normallyClosed) {
        super(resistance, node1, node2);
        this.coilWire = coilWire;
        this.onCurrent = onCurrent;
        this.offCurrent = offCurrent;
        this.normallyClosed = normallyClosed;
        this.polarized = false;
    }

    public RelaySwitchWire(float resistance, IElectricNode node1, IElectricNode node2, boolean initialState, AbstractElectricWire coilWire, float onCurrent, float offCurrent, boolean normallyClosed, boolean polarized) {
        super(resistance, node1, node2, initialState);
        this.coilWire = coilWire;
        this.onCurrent = onCurrent;
        this.offCurrent = offCurrent;
        this.normallyClosed = normallyClosed;
        this.polarized = polarized;
    }

    @Override
    public void preSolve() {
        if(coilWire.isConverged()) {
            if (polarized) {
                var I = coilWire.current();
                if ((getState() == normallyClosed) && I > onCurrent) { // Forward
                    setState(!normallyClosed);
                    switched = true;
                } else if ((getState() != normallyClosed) && I < -onCurrent) { // Reverse
                    setState(normallyClosed);
                    switched = true;
                }
            } else {
                // The settled magnitude, not the instantaneous one. A sinusoidal coil current
                // passes through zero twice per cycle whatever its amplitude, so comparing the
                // instantaneous value against the drop-out threshold released the armature every
                // half cycle even when the coil was driven hard -- 71% of the time at the pull-in
                // current, 30% at twice it. That is a relay buzzing at twice supply frequency.
                var I = coilWire.lastRmsCurrent();
                if ((getState() != normallyClosed) && I < offCurrent) {
                    setState(normallyClosed);
                    switched = true;
                } else if ((getState() == normallyClosed) && I > onCurrent) {
                    setState(!normallyClosed);
                    switched = true;
                }
            }
        }
    }

    public boolean wasSwitched() {
        boolean was = switched;
        switched = false;
        return was;
    }
}
