package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.helmet.HelmetConnectionState
import de.werklade.cratoniremote.helmet.HelmetFailure
import de.werklade.cratoniremote.helmet.HelmetSppClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [helmetShouldConnect] is what makes the app notice a helmet that is switched on after the remote is already
 * connected (device test 17.10.2026). It is also the rule that keeps the app from opening a socket because a
 * stranger's headset connected to the phone, so every branch is tested here.
 *
 * Addresses are examples, as everywhere in this project; no real device address is in the source.
 */
class HelmetAclGateTest {

    private val prefix = HelmetSppClient.HELMET_NAME_PREFIX

    /** The address the client resolved among the bonded devices. */
    private val helmetAddress = "A4:C1:38:1B:2C:3D"

    private val helmet = AclDevice(address = helmetAddress, name = "SMARTRIDE 1.2-B2C3")

    private val headset = AclDevice(address = "11:22:33:44:55:66", name = "Galaxy Buds3 Pro")

    /** The event the whole receiver exists for: helmet switched on while the remote is connected. */
    @Test
    fun `the helmet switched on while the remote is connected is connected to`() {
        assertTrue(shouldConnect(helmet))
    }

    /** A helmet that was off at "remote connected" left the client in exactly these states. */
    @Test
    fun `every state that is not connected or connecting leads to a connection`() {
        listOf(
            HelmetConnectionState.Disconnected,
            HelmetConnectionState.NotFound,
            HelmetConnectionState.Failed(HelmetFailure.CONNECT),
            HelmetConnectionState.Failed(HelmetFailure.SEND),
            HelmetConnectionState.Failed(HelmetFailure.BLUETOOTH_UNAVAILABLE),
            HelmetConnectionState.Failed(HelmetFailure.MISSING_PERMISSION),
        ).forEach { state ->
            assertTrue("$state", shouldConnect(helmet, helmetState = state))
        }
    }

    @Test
    fun `a standing or starting connection is left alone`() {
        assertFalse(shouldConnect(helmet, helmetState = HelmetConnectionState.Connected))
        assertFalse(shouldConnect(helmet, helmetState = HelmetConnectionState.Connecting))
    }

    /** Somebody else's headset or phone connecting to this phone must not open a socket. */
    @Test
    fun `another device is ignored`() {
        assertFalse(shouldConnect(headset))
    }

    /**
     * The address of the device the client itself resolved wins: that is the one
     * [HelmetSppClient.connect] would talk to, so a second device of the same model is not worth an attempt.
     */
    @Test
    fun `a matching name does not help another address`() {
        val otherHelmet = AclDevice(address = "11:22:33:44:55:66", name = "SMARTRIDE 1.2-9F8E")

        assertFalse(shouldConnect(otherHelmet))
    }

    /** Before any [HelmetSppClient.connect] resolved a device, the name prefix is all there is. */
    @Test
    fun `without a known address the name prefix decides`() {
        assertTrue(shouldConnect(helmet, helmetAddress = null))
        assertFalse(shouldConnect(headset, helmetAddress = null))
    }

    /** Same comparison as `HelmetSppClient.findHelmet`, which ignores case. */
    @Test
    fun `the name prefix is compared ignoring case`() {
        val lowercase = AclDevice(address = helmetAddress, name = "smartride 1.2-b2c3")

        assertTrue(shouldConnect(lowercase, helmetAddress = null))
    }

    /** The address is compared ignoring case too; the stack is not guaranteed to report one spelling. */
    @Test
    fun `the address is compared ignoring case`() {
        val lowercase = AclDevice(address = helmetAddress.lowercase(), name = null)

        assertTrue(shouldConnect(lowercase))
    }

    /** Nothing readable in the broadcast is not a reason to connect to anything. */
    @Test
    fun `a device without address and name is ignored`() {
        assertFalse(shouldConnect(AclDevice(address = null, name = null)))
        assertFalse(shouldConnect(AclDevice(address = null, name = null), helmetAddress = null))
    }

    /** No known address and no name: the prefix cannot be applied, so the event says nothing. */
    @Test
    fun `without a known address a nameless device is ignored`() {
        assertFalse(shouldConnect(AclDevice(address = helmetAddress, name = null), helmetAddress = null))
    }

    /**
     * While a confirmation runs, the connection to the helmet is held back on purpose ([dispatchAllowed]):
     * the remote whose press is awaited may belong to somebody else, and the helmet that just connected may
     * be the one of the stranger whose remote is being looked at.
     */
    @Test
    fun `nothing is connected while a confirmation runs`() {
        assertFalse(shouldConnect(helmet, dispatchAllowed = false))
    }

    /** Without the remote there is no light button to serve. */
    @Test
    fun `nothing is connected while the remote is not connected`() {
        assertFalse(shouldConnect(helmet, remoteConnected = false))
    }

    // The disconnect path uses the device rule alone, so it is tested on its own as well.

    @Test
    fun `the device rule recognises the helmet and nothing else`() {
        assertTrue(isHelmetDevice(helmet, helmetAddress, prefix))
        assertFalse(isHelmetDevice(headset, helmetAddress, prefix))
        assertTrue(isHelmetDevice(helmet, helmetAddress = null, namePrefix = prefix))
        assertFalse(isHelmetDevice(headset, helmetAddress = null, namePrefix = prefix))
        assertFalse(isHelmetDevice(AclDevice(null, null), helmetAddress, prefix))
    }

    private fun shouldConnect(
        device: AclDevice,
        helmetAddress: String? = this.helmetAddress,
        helmetState: HelmetConnectionState = HelmetConnectionState.Failed(HelmetFailure.CONNECT),
        dispatchAllowed: Boolean = true,
        remoteConnected: Boolean = true,
    ): Boolean = helmetShouldConnect(
        device = device,
        helmetAddress = helmetAddress,
        namePrefix = prefix,
        helmetState = helmetState,
        dispatchAllowed = dispatchAllowed,
        remoteConnected = remoteConnected,
    )
}
