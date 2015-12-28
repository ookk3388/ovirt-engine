package org.ovirt.engine.core.bll;

import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.FeatureSupported;
import org.ovirt.engine.core.common.action.VmSlaPolicyParameters;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.vdscommands.UpdateVmPolicyVDSParams;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;

/**
 * VmSlaPolicyCommand, This command will push SLA parameters such as CPU, RAM and IO
 * tuning to the VM. This Command runs as a hot plug (when the VM is running).
 *
 * The execute will never throw an exception. it will rather wrap a return value in case
 * of failure.
 */
@NonTransactiveCommandAttribute
public class VmSlaPolicyCommand<T extends VmSlaPolicyParameters> extends VmManagementCommandBase<T> {

    public static final String LOGABLE_FIELD_CPU_LIMIT = "cpuLimit";

    public VmSlaPolicyCommand(T parameters) {
        super(parameters);
        if (getParameters().getVm() != null) {
            setVm(getParameters().getVm());
        }
    }

    @Override
    protected boolean validate() {
        if (getVm() == null) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_VM_NOT_FOUND);
        }
        if (getVm().getStatus() != VMStatus.Up) {
            return failVmStatusIllegal();
        }
        if (!FeatureSupported.vmSlaPolicy(getVm().getClusterCompatibilityVersion())) {
            return failValidation(EngineMessage.VM_SLA_POLICY_NOT_SUPPORTED);
        }

        return true;
    }

    /**
     * Execution shall perform a call to VDSM to set the SLA parameters.
     */
    @Override
    protected void executeCommand() {
        VDSReturnValue vdsReturnValue = runVdsCommand(VDSCommandType.UpdateVmPolicy,
                new UpdateVmPolicyVDSParams(getVm().getRunOnVds(), getVmId(), getParameters().getCpuLimit()));

        setSucceeded(vdsReturnValue.getSucceeded());
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        addCustomValue(LOGABLE_FIELD_CPU_LIMIT,
                String.valueOf(getParameters().getCpuLimit()));

        return getSucceeded() ? AuditLogType.VM_SLA_POLICY : AuditLogType.FAILED_VM_SLA_POLICY;
    }

    @Override
    protected void setActionMessageParameters() {
        addValidationMessage(EngineMessage.VAR__ACTION__UPDATE_SLA_POLICY);
        addValidationMessage(EngineMessage.VAR__TYPE__VM);
    }
}
