package com.kerosene.kfe.adapters.out.rail.channels;

import java.util.List;

/**
 * Fail-closed channel gateway when LND REST is disabled.
 * Registered via {@link LightningChannelGatewayConfiguration} only when no other
 * {@link LightningChannelGateway} bean exists.
 */
public class DisabledLightningChannelGateway implements LightningChannelGateway {

    /** @return {@code false} because no live Lightning provider is configured */
    @Override
    public boolean isLive() {
        return false;
    }

    /** @return constant provider marker used to identify the disabled adapter */
    @Override
    public String providerName() {
        return "DISABLED";
    }

    /** @return empty inventory because disabled mode cannot query a Lightning node */
    @Override
    public List<ChannelSnapshot> listChannels() {
        return List.of();
    }

    /**
     * Rejects opening because the configured gateway is disabled.
     * @param command requested peer and channel parameters, unused while disabled
     * @return never returns; throws to prevent treating a disabled integration as successful
     * @throws IllegalStateException always, because the provider is not live
     */
    @Override
    public OpenChannelResult openChannel(OpenChannelCommand command) {
        throw new IllegalStateException("Lightning channel gateway is not live.");
    }

    /**
     * Rejects closing because the configured gateway is disabled.
     * @param command target channel and close mode, unused while disabled
     * @return never returns; throws to prevent a false close acknowledgement
     * @throws IllegalStateException always, because the provider is not live
     */
    @Override
    public CloseChannelResult closeChannel(CloseChannelCommand command) {
        throw new IllegalStateException("Lightning channel gateway is not live.");
    }

    /**
     * Rejects policy changes because the configured gateway is disabled.
     * @param command target channel and fee settings, unused while disabled
     * @return never returns; throws to prevent a false policy-update acknowledgement
     * @throws IllegalStateException always, because the provider is not live
     */
    @Override
    public UpdatePolicyResult updateChannelPolicy(UpdatePolicyCommand command) {
        throw new IllegalStateException("Lightning channel gateway is not live.");
    }
}
