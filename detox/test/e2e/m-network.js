const MockServer = require('../mock-server/mock-server');

describe('Network Synchronization', () => {
  let mockServer = new MockServer();

  before(async () => {
    mockServer.init();
  });

  after( () => {
    mockServer.close();
  });

  beforeEach(async () => {
    await device.reloadReactNative();
    await element(by.label('Network')).tap();
  });

  it('Sync with short network requests - 100ms', async () => {
    await element(by.id('ShortNetworkRequest')).tap();
    await expect(element(by.label('Short Network Request Working!!!'))).toBeVisible();
  });

  it('Sync with long network requests - 3000ms', async () => {
    await element(by.id('LongNetworkRequest')).tap();
    await expect(element(by.label('Long Network Request Working!!!'))).toBeVisible();
  });

  it('disableSynchronization() should disable sync', async () => {
    await device.disableSynchronization();

    await element(by.id('LongNetworkRequest')).tap();
    await expect(element(by.label('Long Network Request Working!!!'))).toBeNotVisible();
    await waitFor(element(by.label('Long Network Request Working!!!'))).toBeVisible().withTimeout(4000);
    await expect(element(by.label('Long Network Request Working!!!'))).toBeVisible();

    await device.enableSynchronization();
  });


  it('setURLBlacklist() should disable synchronization for given endpoint', async () => {
    await device.setURLBlacklist(['.*127.0.0.1.*']);

    await element(by.id('LongNetworkRequest')).tap();
    await expect(element(by.label('Long Network Request Working!!!'))).toBeNotVisible();
    await waitFor(element(by.label('Long Network Request Working!!!'))).toBeVisible().withTimeout(4000);
    await expect(element(by.label('Long Network Request Working!!!'))).toBeVisible();

    await device.setURLBlacklist([]);
  });
});
